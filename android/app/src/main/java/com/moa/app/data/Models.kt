package com.moa.app.data

import org.json.JSONObject

data class Member(val id: String, val displayName: String)
data class Session(val serverUrl: String, val token: String, val expiresAt: String, val member: Member)
data class Photo(
    val id: String, val width: Int, val height: Int, val mimeType: String, val byteSize: Long,
    val thumbnailUrl: String, val previewUrl: String, val originalUrl: String,
)
data class Post(
    val id: Long, val author: Member, val caption: String, val createdAt: String,
    val photos: List<Photo>, val likeCount: Int, val likedByMe: Boolean,
)
data class Feed(val posts: List<Post>, val nextCursor: Long?)
class ApiException(val status: Int, message: String) : Exception(message)

internal fun JSONObject.member() = Member(getString("id"), getString("displayName"))
internal fun JSONObject.post(): Post {
    val images = getJSONArray("photos")
    return Post(getLong("id"), getJSONObject("author").member(), getString("caption"), getString("createdAt"),
        List(images.length()) { index ->
            val image = images.getJSONObject(index)
            Photo(image.getString("id"), image.getInt("width"), image.getInt("height"), image.getString("mimeType"),
                image.getLong("byteSize"), image.getString("thumbnailUrl"), image.getString("previewUrl"), image.getString("originalUrl"))
        }, getInt("likeCount"), getBoolean("likedByMe"))
}
