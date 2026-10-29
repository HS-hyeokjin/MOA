package com.moa.app.data

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import android.provider.OpenableColumns
import com.moa.app.BuildConfig
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSink
import org.json.JSONArray
import org.json.JSONObject

class MoaRepository(private val context: Context) {
    val client = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(90, TimeUnit.SECONDS).callTimeout(180, TimeUnit.SECONDS)
        .followRedirects(false).followSslRedirects(false).build()

    fun validateServer(value: String): String = normalizeServerAddress(value, BuildConfig.DEBUG)

    suspend fun login(server: String, code: String): Session {
        val base = validateServer(server)
        val payload = JSONObject().put("code", code.trim()).toString().toRequestBody("application/json".toMediaType())
        val json = JSONObject(text(Request.Builder().url("$base/api/v1/auth/login").post(payload).build()))
        return Session(base, json.getString("token"), json.getString("expiresAt"), json.getJSONObject("member").member())
    }
    private fun request(session: Session, path: String): Request.Builder {
        require(path.startsWith("/api/v1/") && !path.contains(".."))
        return Request.Builder().url(session.serverUrl + path).header("Authorization", "Bearer ${session.token}")
    }
    suspend fun me(session: Session) = JSONObject(text(request(session, "/api/v1/me").build())).member()
    suspend fun members(session: Session): List<Member> {
        val json = JSONArray(text(request(session, "/api/v1/members").build()))
        return List(json.length()) { json.getJSONObject(it).member() }
    }
    suspend fun feed(session: Session, before: Long? = null): Feed {
        val json = JSONObject(text(request(session, "/api/v1/posts" + (before?.let { "?before=$it" } ?: "")).build()))
        val posts = json.getJSONArray("posts")
        return Feed(List(posts.length()) { posts.getJSONObject(it).post() }, if (json.isNull("nextCursor")) null else json.getLong("nextCursor"))
    }
    suspend fun like(session: Session, post: Post): Post {
        val builder = request(session, "/api/v1/posts/${post.id}/like")
        if (post.likedByMe) builder.delete() else builder.put(ByteArray(0).toRequestBody())
        return JSONObject(text(builder.build())).post()
    }
    suspend fun delete(session: Session, post: Post) { text(request(session, "/api/v1/posts/${post.id}").delete().build()) }
    suspend fun logout(session: Session) { text(request(session, "/api/v1/auth/logout").post(ByteArray(0).toRequestBody()).build()) }

    suspend fun race(session: Session): RaceGame = JSONObject(text(request(session, "/api/v1/games/race").build())).raceGame()
    suspend fun rollRace(session: Session, requestId: String): RaceOutcome {
        val json = JSONObject(text(request(session, "/api/v1/games/race/rolls").header("Idempotency-Key", requestId)
            .post(ByteArray(0).toRequestBody()).build()))
        return RaceOutcome(json.getJSONObject("game").raceGame(), json.getJSONObject("roll").raceRoll())
    }

    suspend fun upload(session: Session, uris: List<Uri>, caption: String, requestId: String, progress: (Float) -> Unit): Post = withContext(Dispatchers.IO) {
        require(uris.size in 1..5) { "사진을 1~5장 선택해 주세요." }
        val sizes = uris.map { uri ->
            context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getLong(0) else -1L
            } ?: -1L
        }
        require(sizes.none { it > MAX_PHOTO_BYTES }) { "사진은 한 장에 10MB까지 올릴 수 있어요." }
        var sent = 0L
        val total = sizes.filter { it > 0 }.sum().coerceAtLeast(1)
        val builder = MultipartBody.Builder().setType(MultipartBody.FORM).addFormDataPart("caption", caption)
        uris.forEachIndexed { index, uri ->
            val mime = context.contentResolver.getType(uri)
            require(mime in listOf("image/jpeg", "image/png")) { "현재 JPG와 PNG 사진을 올릴 수 있어요." }
            val body = object : RequestBody() {
                override fun contentType() = mime!!.toMediaType()
                override fun contentLength() = sizes[index]
                override fun writeTo(sink: BufferedSink) {
                    val stream = context.contentResolver.openInputStream(uri) ?: throw IOException("사진을 읽지 못했어요.")
                    stream.use { input ->
                        val buffer = ByteArray(16_384)
                        var bytes = 0L
                        for (count in generateSequence { input.read(buffer).takeIf { it != -1 } }) {
                            bytes += count
                            if (bytes > MAX_PHOTO_BYTES) throw IOException("사진은 한 장에 10MB까지 올릴 수 있어요.")
                            sink.write(buffer, 0, count)
                            sent += count
                            progress((sent.toFloat() / total).coerceIn(0f, 1f))
                        }
                    }
                }
            }
            builder.addFormDataPart("photos", "photo-${index + 1}" + if (mime == "image/png") ".png" else ".jpg", body)
        }
        JSONObject(text(request(session, "/api/v1/posts").header("Idempotency-Key", requestId).post(builder.build()).build())).post()
    }

    suspend fun download(session: Session, photo: Photo) = withContext(Dispatchers.IO) {
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, "MOA-${photo.id}" + if (photo.mimeType == "image/png") ".png" else ".jpg")
            put(MediaStore.Downloads.MIME_TYPE, photo.mimeType)
            put(MediaStore.Downloads.RELATIVE_PATH, "Download/MOA")
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val destination = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: throw IOException("저장 공간을 확인해 주세요.")
        try {
            await(request(session, photo.originalUrl).build()).use { response ->
                checkResponse(response)
                val body = response.body ?: throw IOException("사진을 받지 못했어요.")
                resolver.openOutputStream(destination)?.use { output ->
                    body.byteStream().use { input ->
                        val buffer = ByteArray(16_384)
                        var total = 0L
                        for (count in generateSequence { input.read(buffer).takeIf { it != -1 } }) {
                            total += count
                            if (total > MAX_PHOTO_BYTES) throw IOException("사진 크기를 확인하지 못했어요.")
                            output.write(buffer, 0, count)
                        }
                        if (total != photo.byteSize) throw IOException("사진을 끝까지 받지 못했어요. 다시 시도해 주세요.")
                    }
                } ?: throw IOException("사진을 저장하지 못했어요.")
            }
            resolver.update(destination, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
        } catch (error: Exception) { resolver.delete(destination, null, null); throw error }
    }

    private suspend fun text(request: Request): String = withContext(Dispatchers.IO) {
        await(request).use { response -> checkResponse(response); response.body?.string() ?: "" }
    }
    private fun checkResponse(response: Response) {
        if (!response.isSuccessful) {
            val message = try { JSONObject(response.body?.string() ?: "{}").optString("message") } catch (_: Exception) { "" }
            throw ApiException(response.code, message.ifBlank { "요청을 처리하지 못했어요. 다시 시도해 주세요." })
        }
    }
    private suspend fun await(request: Request): Response = suspendCancellableCoroutine { continuation ->
        val call = client.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) { if (continuation.isActive) continuation.resumeWithException(e) }
            override fun onResponse(call: Call, response: Response) {
                continuation.resume(response, onCancellation = { _, resource, _ -> resource.close() })
            }
        })
    }
    companion object { const val MAX_PHOTO_BYTES = 10L * 1024 * 1024 }
}
