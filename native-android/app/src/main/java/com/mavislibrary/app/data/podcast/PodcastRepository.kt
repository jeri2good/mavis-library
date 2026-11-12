package com.mavislibrary.app.data.podcast

import com.mavislibrary.app.data.api.ApiException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException
import java.time.Instant

sealed interface PodcastPoll {
    data class Updated(val job: PodcastJob) : PodcastPoll
    data object Unreachable : PodcastPoll
    data object TimedOut : PodcastPoll
}
class PodcastRepository(
    private val api: PodcastService, private val store: PodcastLibrary, val studio: String,
    private val clock: () -> Long = System::currentTimeMillis,
    private val accountId: suspend () -> String? = { null },
) {
    /** Guards the library and the sets below. Never held across a network call, so a slow list can't hold up a status save. */
    private val mutation = Mutex()
    private val deleted = mutableSetOf<String>()
    /** Created at the studio but not yet written to the phone (the write failed); written on the next refresh. */
    private val unsaved = mutableListOf<SavedPodcast>()
    /** Requests whose reply never arrived, with the podcasts known at the time, so a second tap can find the first result. */
    private class Lost(val request: PodcastRequest, val known: Set<String>, val at: Long)
    private val lost = mutableListOf<Lost>()
    private val wakingRequests = MutableStateFlow(0)
    val wakingStudio = wakingRequests.map { it > 0 }.distinctUntilChanged()
    private fun Exception.isStudioColdStart() = this is ApiException && status == 502 && code == "studio_unavailable"
    private fun Exception.isGone() = this is ApiException && status == 404
    /** The request may have reached the gateway, but no answer came back. */
    private fun Exception.replyLost() = this is IOException && (this !is ApiException || status == null)
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
    private suspend fun mine(account: String?) = store.library.first().filter { it.studio == studio && it.accountId == account }

    /**
     * Asks the studio for a podcast and remembers it. Safe against the two ways this used to double up or lose work:
     * when the reply is lost on the way back, the studio's list is searched for the podcast before anything is sent
     * again (now, and on the next attempt with the same request); and when only the phone's own write fails, the
     * podcast is still reported as made and written later, instead of looking like a failure that invites a re-send.
     */
    suspend fun create(request: PodcastRequest): SavedPodcast {
        val checked = request.validated()
        val account = accountId()
        val pending = mutation.withLock { lost.removeAll { clock() - it.at > LOST_WINDOW_MS }; lost.find { it.request == checked } }
        if (pending != null) {
            // If the list can't be read either, stop here: sending again blind is exactly how duplicates were made.
            adopt(pending, account)?.let { return it }
            mutation.withLock { lost.remove(pending) }
        }
        val known = store.library.first().filter { it.studio == studio }.map { it.job.id }.toSet()
        val started = clock()
        val created = try { studioCall { api.create(checked) } } catch (e: CancellationException) { throw e } catch (e: Exception) {
            if (!e.replyLost()) throw e
            val entry = Lost(checked, known, started)
            mutation.withLock { lost += entry }
            return runCatching { adopt(entry, account) }.getOrElse { if (it is CancellationException) throw it else null } ?: throw e
        }
        StudioAddress.safeId(created.id)
        val saved = SavedPodcast(
            PodcastJob(created.id, created.status, title = checked.title, kind = checked.kind, style = checked.style, createdAt = Instant.ofEpochMilli(started).toString()),
            studio, request = checked, accountId = account,
        )
        try { mutation.withLock { store.save(saved) } } catch (e: CancellationException) { throw e } catch (_: Exception) { mutation.withLock { unsaved += saved } }
        return saved
    }

    /** Looks in the studio's list for the podcast a lost reply belonged to, and keeps it with its retry source. */
    private suspend fun adopt(entry: Lost, account: String?): SavedPodcast? {
        val listing = api.listing()
        return mutation.withLock {
            val have = store.library.first().filter { it.studio == studio }.map { it.job.id }.toSet()
            val match = listing.jobs.filter { job ->
                job.id !in entry.known && job.id !in have && job.id !in deleted && job.title.trim() == entry.request.title.trim() &&
                    (job.kind.isBlank() || job.kind == entry.request.kind) &&
                    (runCatching { Instant.parse(job.createdAt).toEpochMilli() }.getOrNull()?.let { it >= entry.at - CLOCK_SLACK_MS } ?: true)
            }.maxByOrNull { it.createdAt } ?: return@withLock null
            lost.remove(entry)
            SavedPodcast(match, studio, request = entry.request, accountId = account).also { store.save(it) }
        }
    }

    /**
     * Tries a failed podcast again, replacing it. First asks the studio how the old one stands: if it isn't failed
     * after all, nothing new is made. Otherwise a new podcast is requested and the failed one is removed.
     */
    suspend fun retry(saved: SavedPodcast): SavedPodcast {
        val request = saved.request ?: throw IllegalStateException("This podcast can't be sent again from this phone. Make it again with New podcast.")
        val latest = try { check(saved.job.id) } catch (e: ApiException) { if (e.isGone()) null else throw e }
        if (latest != null && latest.status != "failed") return store.library.first().find { it.key == saved.key } ?: saved.copy(job = latest)
        val fresh = create(request)
        if (latest != null) {
            // Best effort: if the studio can't delete the old one now, it simply stays listed as failed.
            val gone = try { api.delete(saved.job.id); true } catch (e: CancellationException) { throw e } catch (e: Exception) { e.isGone() }
            if (gone) mutation.withLock { deleted += saved.job.id; store.remove(saved.job.id, studio) }
        }
        return fresh
    }

    /**
     * Brings the phone's library in line with the studio's list in one write. Podcasts the phone has that a complete
     * list lacks are checked one by one, and removed only when the studio confirms they are gone.
     */
    suspend fun refresh(): List<PodcastJob> {
        val account = accountId()
        val listing = studioCall { api.listing() }
        val jobs = mutation.withLock {
            val jobs = listing.jobs.filter { it.id !in deleted && runCatching { StudioAddress.safeId(it.id) }.isSuccess }
            val late = unsaved.filterNot { it.job.id in deleted }
            store.update { current ->
                val byKey = LinkedHashMap(current.associateBy { it.key })
                (late + jobs.map { SavedPodcast(it, studio, accountId = account) }).forEach { byKey[it.key] = it.mergedOver(byKey[it.key]) }
                byKey.values.toList()
            }
            unsaved.clear()
            jobs
        }
        if (listing.complete) {
            val listed = jobs.map { it.id }.toSet()
            mine(account).filter { it.job.id !in listed && it.localFile == null }.take(MAX_CONFIRMATIONS).forEach { missing ->
                try { check(missing.job.id) } catch (e: CancellationException) { throw e } catch (_: Exception) { /* gone ones are removed by check; anything else waits */ }
            }
        }
        return jobs
    }
    suspend fun delete(id: String) {
        api.delete(id)
        mutation.withLock { deleted += id; store.remove(id, studio) }
    }
    /** Forgets a podcast the studio no longer has, unless a downloaded copy can still be played. */
    private suspend fun forget(id: String) = mutation.withLock {
        val held = store.library.first().find { it.job.id == id && it.studio == studio }
        if (held != null && held.localFile == null) store.remove(id, studio)
    }
    /** One status read; saves the job. A podcast the studio no longer has is removed from the phone and the error rethrown. */
    suspend fun check(id: String): PodcastJob {
        val account = accountId()
        val job = try { studioCall { api.get(StudioAddress.safeId(id)) } } catch (e: ApiException) {
            if (e.isGone()) forget(id)
            throw e
        }
        require(job.id == id) { "Studio returned a different podcast." }
        mutation.withLock { if (id !in deleted) store.save(SavedPodcast(job, studio, accountId = account).mergedOver(store.library.first().find { it.job.id == id && it.studio == studio })) }
        return job
    }
    /** Follows one podcast until it finishes. The Lounge screen uses [PodcastSync] instead, which shares one request budget across podcasts. */
    fun poll(id: String, timeoutMs: Long = 25 * 60_000L): Flow<PodcastPoll> = flow {
        val account = accountId()
        val complete = withTimeoutOrNull(timeoutMs) {
            var failures = 0
            while (true) {
                val job = try { studioCall { api.get(id) } } catch (e: CancellationException) { throw e } catch (e: Exception) {
                    if (e.isGone()) forget(id)
                    if (e.isStudioColdStart() || e is ApiException && e.status in setOf(401, 403, 404, 429)) throw e
                    failures++
                    emit(PodcastPoll.Unreachable)
                    delay((3_000L shl (failures - 1).coerceAtMost(4)).coerceAtMost(30_000))
                    continue
                }
                require(job.id == id) { "Studio returned a different podcast." }
                val saved = mutation.withLock {
                    if (id in deleted) false else { store.save(SavedPodcast(job, studio, accountId = account).mergedOver(store.library.first().find { it.job.id == id && it.studio == studio })); true }
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

    private companion object {
        /** How long a lost reply is remembered. */
        const val LOST_WINDOW_MS = 15 * 60_000L
        /** Allowance for the studio's clock differing from the phone's. */
        const val CLOCK_SLACK_MS = 2 * 60_000L
        /** Missing podcasts confirmed per refresh, to keep a refresh cheap. */
        const val MAX_CONFIRMATIONS = 5
    }
}
