package com.moa.app.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.moa.app.data.*
import java.io.IOException
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class MoaState(
    val starting: Boolean = true, val session: Session? = null, val serverUrl: String = "",
    val loginBusy: Boolean = false, val posts: List<Post> = emptyList(), val members: List<Member> = emptyList(),
    val loading: Boolean = false, val nextCursor: Long? = null, val message: String? = null,
    val feedLoaded: Boolean = false,
    val selected: List<Uri> = emptyList(), val caption: String = "", val uploading: Boolean = false,
    val uploadProgress: Float = 0f, val busyPosts: Set<Long> = emptySet(), val downloading: String? = null,
    val race: RaceGame? = null, val raceLoading: Boolean = false, val raceRolling: Boolean = false,
    val racePending: Boolean = false, val raceError: String? = null, val lastRoll: RaceRoll? = null,
)

class MoaViewModel(application: Application) : AndroidViewModel(application) {
    private val store = SessionStore(application)
    val repository = MoaRepository(application)
    private val mutable = MutableStateFlow(MoaState())
    val state = mutable.asStateFlow()
    private var uploadId = UUID.randomUUID().toString()
    private var feedJob: Job? = null
    private val pendingRace = PendingRaceRoll(application)
    private var raceJob: Job? = null

    init {
        viewModelScope.launch {
            val session = withContext(Dispatchers.IO) { store.load() }
            val valid = session?.takeIf { runCatching { Instant.parse(it.expiresAt).isAfter(Instant.now()) }.getOrDefault(false) }
            if (valid == null && session != null) withContext(Dispatchers.IO) { store.clear() }
            mutable.update { it.copy(starting = false, session = valid, serverUrl = store.lastServer()) }
            if (valid != null) {
                try {
                    val member = repository.me(valid)
                    val refreshed = valid.copy(member = member)
                    withContext(Dispatchers.IO) { store.save(refreshed) }
                    mutable.update { it.copy(session = refreshed) }
                    refresh()
                } catch (error: Exception) { handle(error) }
            }
        }
    }
    fun server(value: String) { mutable.update { it.copy(serverUrl = value) } }
    fun login(code: String) {
        if (state.value.loginBusy) return
        mutable.update { it.copy(loginBusy = true, message = null) }
        viewModelScope.launch {
            try {
                val session = repository.login(state.value.serverUrl, code)
                withContext(Dispatchers.IO) { store.save(session) }
                mutable.update { it.copy(session = session, selected = emptyList(), caption = "") }
                refresh()
            } catch (error: Exception) { handle(error) }
            finally { mutable.update { it.copy(loginBusy = false) } }
        }
    }
    fun refresh(more: Boolean = false) {
        val current = state.value
        val session = current.session ?: return
        if (current.loading || current.uploading || current.busyPosts.isNotEmpty() || (more && current.nextCursor == null)) return
        mutable.update { it.copy(loading = true) }
        feedJob = viewModelScope.launch {
            try {
                val feed = repository.feed(session, if (more) current.nextCursor else null)
                val members = if (current.members.isEmpty()) repository.members(session) else current.members
                if (state.value.session?.token == session.token) mutable.update {
                    it.copy(posts = if (more) (it.posts + feed.posts).distinctBy(Post::id) else feed.posts, nextCursor = feed.nextCursor, members = members, feedLoaded = true)
                }
            } catch (error: Exception) { handle(error) }
            finally { mutable.update { it.copy(loading = false) } }
        }
    }
    fun select(uris: List<Uri>) {
        if (state.value.uploading) return
        uploadId = UUID.randomUUID().toString()
        mutable.update { it.copy(selected = uris.distinct().take(5), uploadProgress = 0f) }
        if (uris.size > 5) message("사진은 한 번에 5장까지 올릴 수 있어요.")
    }
    fun caption(value: String) {
        if (state.value.uploading) return
        uploadId = UUID.randomUUID().toString()
        mutable.update { it.copy(caption = value.take(500)) }
    }
    fun upload() {
        val current = state.value
        val session = current.session ?: return
        if (current.uploading || current.loading || current.selected.isEmpty()) return
        mutable.update { it.copy(uploading = true, uploadProgress = 0f) }
        viewModelScope.launch {
            try {
                val post = repository.upload(session, current.selected, current.caption, uploadId) { progress ->
                    mutable.update { it.copy(uploadProgress = progress) }
                }
                mutable.update { it.copy(posts = (listOf(post) + it.posts).distinctBy(Post::id), selected = emptyList(), caption = "", feedLoaded = true, message = "가족에게 사진을 공유했어요.") }
                uploadId = UUID.randomUUID().toString()
            } catch (error: Exception) { handle(error) }
            finally { mutable.update { it.copy(uploading = false) } }
        }
    }
    fun like(post: Post) = change(post) { session -> repository.like(session, post) }
    fun delete(post: Post) = change(post) { session -> repository.delete(session, post); null }
    private fun change(post: Post, action: suspend (Session) -> Post?) {
        val current = state.value
        val session = current.session ?: return
        if (current.loading || post.id in current.busyPosts) return
        mutable.update { it.copy(busyPosts = it.busyPosts + post.id) }
        viewModelScope.launch {
            try {
                val updated = action(session)
                mutable.update { it.copy(posts = if (updated == null) it.posts.filterNot { item -> item.id == post.id }
                    else it.posts.map { item -> if (item.id == post.id) updated else item }) }
            } catch (error: Exception) { handle(error) }
            finally { mutable.update { it.copy(busyPosts = it.busyPosts - post.id) } }
        }
    }
    fun download(photo: Photo) {
        val session = state.value.session ?: return
        if (state.value.downloading != null) return
        mutable.update { it.copy(downloading = photo.id) }
        viewModelScope.launch {
            try { repository.download(session, photo); message("다운로드의 MOA 폴더에 원본을 저장했어요.") }
            catch (error: Exception) { handle(error) }
            finally { mutable.update { it.copy(downloading = null) } }
        }
    }
    fun refreshRace() {
        val current = state.value
        val session = current.session ?: return
        if (current.raceLoading || current.raceRolling || current.loginBusy) return
        mutable.update { it.copy(raceLoading = true, raceError = null) }
        raceJob = viewModelScope.launch {
            try {
                val pending = withContext(Dispatchers.IO) { pendingRace.exists(session) }
                val game = repository.race(session)
                if (state.value.session?.token == session.token) mutable.update {
                    it.copy(race = game, racePending = pending, lastRoll = it.lastRoll?.takeIf { roll -> roll.day == game.today })
                }
            } catch (error: Exception) { raceFailure(error) }
            finally { mutable.update { it.copy(raceLoading = false) } }
        }
    }
    fun rollRace() {
        val current = state.value
        val session = current.session ?: return
        if (current.raceRolling || current.raceLoading || current.loginBusy || current.race == null ||
            (current.race.remainingRolls <= 0 && !current.racePending)) return
        mutable.update { it.copy(raceRolling = true, raceError = null) }
        raceJob = viewModelScope.launch {
            var refreshAfter = false
            try {
                val requestId = withContext(Dispatchers.IO) { pendingRace.getOrCreate(session) }
                mutable.update { it.copy(racePending = true) }
                val result = repository.rollRace(session, requestId)
                withContext(Dispatchers.IO) { pendingRace.clear(session) }
                if (state.value.session?.token == session.token) mutable.update {
                    it.copy(race = result.game, lastRoll = result.roll, racePending = false)
                }
            } catch (error: Exception) {
                if (error is ApiException && error.status in listOf(400, 403, 409)) {
                    try {
                        withContext(Dispatchers.IO) { pendingRace.clear(session) }
                        mutable.update { it.copy(racePending = false) }
                    } catch (storageError: Exception) { raceFailure(storageError) }
                    refreshAfter = error.status == 409
                }
                raceFailure(error)
            } finally { mutable.update { it.copy(raceRolling = false) } }
            if (refreshAfter) refreshRace()
        }
    }
    private suspend fun raceFailure(error: Exception) {
        handle(error)
        if (state.value.session != null) mutable.update { it.copy(raceError = it.message) }
    }
    fun logout() {
        val current = state.value
        val session = current.session ?: return
        if (current.uploading || current.loginBusy || current.downloading != null || current.busyPosts.isNotEmpty() || current.raceRolling) return
        mutable.update { it.copy(loginBusy = true) }
        viewModelScope.launch {
            try {
                repository.logout(session)
                feedJob?.cancel()
                raceJob?.cancel()
                withContext(Dispatchers.IO) { store.clear() }
                mutable.value = MoaState(starting = false, serverUrl = session.serverUrl)
            } catch (error: Exception) { handle(error) }
            finally { mutable.update { it.copy(loginBusy = false) } }
        }
    }
    fun message(value: String?) { mutable.update { it.copy(message = value) } }
    private suspend fun handle(error: Exception) {
        if (error is CancellationException) throw error
        if (error is ApiException && error.status == 401 && state.value.session != null) {
            withContext(Dispatchers.IO) { store.clear() }
            mutable.value = MoaState(starting = false, serverUrl = state.value.serverUrl, message = "로그인이 만료됐어요. 새 초대코드로 다시 로그인해 주세요.")
        } else message(when (error) {
            is ApiException, is IllegalArgumentException, is IllegalStateException -> error.message ?: "다시 시도해 주세요."
            is IOException -> "서버에 연결하지 못했어요. 연결을 확인하고 다시 시도해 주세요."
            else -> "요청을 처리하지 못했어요. 다시 시도해 주세요."
        })
    }
}
