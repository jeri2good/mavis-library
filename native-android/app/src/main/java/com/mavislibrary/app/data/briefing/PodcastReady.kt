package com.mavislibrary.app.data.briefing

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import com.mavislibrary.app.data.podcast.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import java.time.Instant

@Serializable data class ReadyHistory(val initializedAt: Long = 0, val pending: Map<String, Long> = emptyMap(), val outcomes: Map<String, Long> = emptyMap())
interface ReadyHistoryStore {
    suspend fun read(account: String): ReadyHistory
    suspend fun write(account: String, value: ReadyHistory)
}
class PodcastReadyStore(private val store: DataStore<Preferences>) : ReadyHistoryStore {
    private fun key(account: String) = stringPreferencesKey("podcast_ready_v1_$account")
    override suspend fun read(account: String) = store.data.first()[key(account)]?.let { podcastJson.decodeFromString<ReadyHistory>(it) } ?: ReadyHistory()
    override suspend fun write(account: String, value: ReadyHistory) { store.edit { it[key(account)] = podcastJson.encodeToString(value) } }
}
data class ReadyNotice(val job: PodcastJob, val outcome: String, val title: String, val text: String) {
    val play get() = outcome == "ready"
}
/** Shared by periodic and expedited workers; no HTTP, Android clock, or notifications in this policy. */
class PodcastReadyRunner(private val history: ReadyHistoryStore, private val clock: () -> Long = System::currentTimeMillis) {
    suspend fun run(account: String, candidates: List<PodcastJob>, check: suspend (String) -> PodcastJob,
                    download: suspend (PodcastJob) -> Unit, allowed: suspend () -> Boolean,
                    notify: suspend (ReadyNotice) -> Unit): Boolean = mutex.withLock {
        val now = clock(); val old = history.read(account)
        val firstRun = old.initializedAt == 0L
        val pending = old.pending.filterValues { now - it < RETAIN }.toMutableMap()
        val outcomes = old.outcomes.filterValues { now - it < RETAIN }.toMutableMap()
        val initialized = if (firstRun) now else old.initializedAt
        suspend fun persist() = history.write(account, ReadyHistory(initialized, pending.toMap(), outcomes.toMap()))
        var retry = false
        // Persist first-run terminal suppression before performing network operations. Pending jobs are eligible later.
        if (firstRun) candidates.filter { it.terminal }.forEach { outcomes["${it.id}/${it.status}"] = now }
        persist()
        for (candidate in candidates.distinctBy { it.id }) {
            if (!allowed()) break
            val created = runCatching { Instant.parse(candidate.createdAt).toEpochMilli() }.getOrNull()
            if (created != null && now - created >= RETAIN) continue
            val known = candidate.id in pending
            if (!candidate.terminal) pending.putIfAbsent(candidate.id, created ?: now)
            // Also suppress old ready episodes discovered after the first run (e.g. a delayed subscription response).
            if (candidate.terminal && !known && (firstRun || created == null || created < initialized)) {
                outcomes.putIfAbsent("${candidate.id}/${candidate.status}", now); persist(); continue
            }
            val job = try { if (candidate.terminal) candidate else check(candidate.id) }
                catch (e: CancellationException) { throw e } catch (_: Exception) { retry = true; candidate }
            val outcome = when {
                job.status == "ready" -> "ready"
                job.status == "failed" -> "failed"
                now - (pending[job.id] ?: created ?: now) >= SIX_HOURS -> "working"
                else -> null
            }
            if (outcome == null) { persist(); continue }
            val key = "${job.id}/$outcome"
            if (key in outcomes) { persist(); continue }
            try {
                if (outcome == "ready") download(job)
                if (!allowed()) break
                val notice = when (outcome) {
                    "ready" -> ReadyNotice(job, outcome, "${job.title} is ready", listOfNotNull(episodeKind(job), job.durationSec?.let { "${maxOf(1, (it + 30) / 60)} min" }, "saved for offline listening").joinToString(" · "))
                    "failed" -> ReadyNotice(job, outcome, "${job.title} couldn't be made", job.error?.takeIf { it.isNotBlank() } ?: "The studio reported a problem.")
                    else -> ReadyNotice(job, outcome, "${job.title} is still working", "Still being made after 6 hours. Check Podcast Lounge later.")
                }
                // Stable notification tag/id replaces the same outcome if execution is interrupted before persistence.
                notify(notice)
                outcomes[key] = now
                if (job.terminal) pending.remove(job.id)
                persist()
            } catch (e: CancellationException) { throw e } catch (_: Exception) { retry = true; persist() }
        }
        persist()
        retry
    }
    companion object { const val SIX_HOURS = 6 * 3600000L; const val RETAIN = 14 * 86400000L; private val mutex = Mutex() }
}
