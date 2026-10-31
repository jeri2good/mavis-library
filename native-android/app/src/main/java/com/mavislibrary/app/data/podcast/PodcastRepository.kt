package com.mavislibrary.app.data.podcast

import com.mavislibrary.app.data.api.ApiException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

sealed interface PodcastPoll {
    data class Updated(val job: PodcastJob) : PodcastPoll
    data object Unreachable : PodcastPoll
    data object TimedOut : PodcastPoll
}
class PodcastRepository(private val api: PodcastService, private val store: PodcastLibrary, val studio: String, private val accountId: suspend () -> String? = { null }) {
    private val mutation = Mutex()
    private val deleted = mutableSetOf<String>()
    private val wakingRequests = MutableStateFlow(0)
    val wakingStudio = wakingRequests.map { it > 0 }.distinctUntilChanged()
    private fun Exception.isStudioColdStart() = this is ApiException && status == 502 && code == "studio_unavailable"
    private suspend fun <T> studioCall(call: suspend () -> T): T {
        var retries = 0
        var waking = false
        try {
            while (true) {
                try { return call() } catch (e: ApiException) {
                    if (!e.isStudioColdStart() || retries++ >= 2) throw e
                    if (!waking) { waking = true; wakingRequests.update { it + 1 } }
                    delay(5_000)
                }
            }
        } finally { if (waking) wakingRequests.update { it - 1 } }
    }
    suspend fun create(request: PodcastRequest): SavedPodcast {
        val checked = request.validated()
        val account = accountId()
        val created = studioCall { api.create(checked) }
        StudioAddress.safeId(created.id)
        return SavedPodcast(PodcastJob(created.id, created.status, title = checked.title, kind = checked.kind, style = checked.style, createdAt = java.time.Instant.now().toString()), studio, request = checked, accountId = account).also { store.save(it) }
    }
    suspend fun refresh(): List<PodcastJob> = mutation.withLock {
        val account = accountId()
        studioCall { api.list() }.filterNot { it.id in deleted }.also { jobs -> jobs.forEach { StudioAddress.safeId(it.id); store.save(SavedPodcast(it, studio, accountId = account)) } }
    }
    suspend fun delete(id: String) = mutation.withLock { api.delete(id); deleted += id; store.remove(id, studio) }
    /** One status read for background checks; saves the job like [poll] does. */
    suspend fun check(id: String): PodcastJob {
        val account = accountId()
        val job = studioCall { api.get(StudioAddress.safeId(id)) }
        require(job.id == id) { "Studio returned a different podcast." }
        mutation.withLock { if (id !in deleted) store.save(SavedPodcast(job, studio, accountId = account)) }
        return job
    }
    fun poll(id: String, timeoutMs: Long = 25 * 60_000L): Flow<PodcastPoll> = flow {
        val account = accountId()
        val complete = withTimeoutOrNull(timeoutMs) {
            var failures = 0
            while (true) {
                val job = try { studioCall { api.get(id) } } catch (e: CancellationException) { throw e } catch (e: Exception) {
                    if (e.isStudioColdStart() || e is ApiException && e.status in setOf(401, 403, 429)) throw e
                    failures++
                    emit(PodcastPoll.Unreachable)
                    delay((3_000L shl (failures - 1).coerceAtMost(4)).coerceAtMost(30_000))
                    continue
                }
                require(job.id == id) { "Studio returned a different podcast." }
                val saved = mutation.withLock {
                    if (id in deleted) false else { store.save(SavedPodcast(job, studio, accountId = account)); true }
                }
                if (!saved) return@withTimeoutOrNull true
                emit(PodcastPoll.Updated(job))
                if (job.terminal) return@withTimeoutOrNull true
                failures = 0
                delay(3_000)
            }
            @Suppress("UNREACHABLE_CODE")
            false
        }
        if (complete == null) emit(PodcastPoll.TimedOut)
    }
}
