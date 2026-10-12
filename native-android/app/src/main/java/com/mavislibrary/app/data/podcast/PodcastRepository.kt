package com.mavislibrary.app.data.podcast

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
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
    suspend fun create(request: PodcastRequest): SavedPodcast {
        val checked = request.validated()
        val account = accountId()
        val created = api.create(checked)
        StudioAddress.safeId(created.id)
        return SavedPodcast(PodcastJob(created.id, created.status, title = checked.title, kind = checked.kind, style = checked.style), studio, request = checked, accountId = account).also { store.save(it) }
    }
    suspend fun refresh(): List<PodcastJob> = mutation.withLock {
        val account = accountId()
        api.list().filterNot { it.id in deleted }.also { jobs -> jobs.forEach { StudioAddress.safeId(it.id); store.save(SavedPodcast(it, studio, accountId = account)) } }
    }
    suspend fun delete(id: String) = mutation.withLock { api.delete(id); deleted += id; store.remove(id, studio) }
    fun poll(id: String, timeoutMs: Long = 25 * 60_000L): Flow<PodcastPoll> = flow {
        val account = accountId()
        val complete = withTimeoutOrNull(timeoutMs) {
            var failures = 0
            while (true) {
                val job = try { api.get(id) } catch (e: CancellationException) { throw e } catch (e: Exception) {
                    if (e is com.mavislibrary.app.data.api.ApiException && e.status in setOf(401, 403, 429)) throw e
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
