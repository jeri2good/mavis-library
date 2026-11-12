package com.mavislibrary.app.data.podcast

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * What the Lounge knows about its link to the studio.
 * [loading] is true while a list request is running; [synced] once one has succeeded since the screen opened.
 * [problem] is why the last list request failed (null after a success). [tracking] holds, per podcast being made,
 * why its last status check failed.
 */
data class PodcastSyncState(
    val loading: Boolean = true,
    val synced: Boolean = false,
    val problem: PodcastProblem? = null,
    val lastSyncMs: Long? = null,
    val tracking: Map<String, PodcastProblem> = emptyMap(),
) {
    /** The very first look hasn't come back yet: nothing is known, which is different from "no podcasts". */
    val firstLoad get() = loading && !synced && problem == null
}

/**
 * Keeps the phone's podcast library current while the Lounge is open. Replaces a 30-second list loop plus one
 * 3-second poll per podcast, which together overran the gateway's per-minute request limit and then stopped quietly.
 *
 * Two loops share one budget:
 *  - the list is read when the screen opens, on [refreshNow], and otherwise every [LIST_EVERY_MS]; after a failure
 *    it backs off from 15 seconds up to five minutes;
 *  - podcasts still being made are checked one after another, a few seconds apart at first and less often as they
 *    age, never faster than the budget allows for how many there are. A failed check is retried with a growing gap
 *    and never ends tracking; a podcast the studio no longer has is dropped by the repository.
 */
class PodcastSync(
    private val refresh: suspend () -> Unit,
    private val check: suspend (String) -> PodcastJob,
    private val library: Flow<List<SavedPodcast>>,
    private val mine: (SavedPodcast) -> Boolean,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    constructor(repository: PodcastRepository, library: Flow<List<SavedPodcast>>, mine: (SavedPodcast) -> Boolean) :
        this({ repository.refresh() }, repository::check, library, mine)

    private val _state = MutableStateFlow(PodcastSyncState())
    val state: StateFlow<PodcastSyncState> = _state.asStateFlow()
    private val listNow = Channel<Unit>(Channel.CONFLATED)
    private val trackNow = Channel<Unit>(Channel.CONFLATED)

    /** Asks for a fresh list and status checks now. Nothing in progress is cancelled. */
    fun refreshNow() { listNow.trySend(Unit); trackNow.trySend(Unit) }

    /** Runs until cancelled; call it for as long as the screen is showing. */
    suspend fun run(): Unit = coroutineScope {
        launch { lists() }
        launch { track() }
    }

    private suspend fun lists() {
        var failures = 0
        while (true) {
            _state.update { it.copy(loading = true) }
            val problem = try { refresh(); null } catch (e: CancellationException) { throw e } catch (e: Exception) { e.toPodcastProblem() }
            failures = if (problem == null) 0 else failures + 1
            _state.update {
                if (problem == null) it.copy(loading = false, synced = true, problem = null, lastSyncMs = clock())
                else it.copy(loading = false, problem = problem)
            }
            withTimeoutOrNull(listWait(problem, failures)) { listNow.receive() }
        }
    }

    private suspend fun track() {
        val age = mutableMapOf<String, Long>()
        var failures = 0
        while (true) {
            val active = library.first().filter { mine(it) && !it.job.terminal }.map { it.job.id }
            age.keys.retainAll(active.toSet())
            _state.update { s -> s.copy(tracking = s.tracking.filterKeys { it in active }) }
            if (active.isEmpty()) {
                failures = 0
                library.first { list -> list.any { mine(it) && !it.job.terminal } }
                continue
            }
            var worst: PodcastProblem? = null
            for (id in active) {
                val problem = try { check(id); null } catch (e: CancellationException) { throw e } catch (e: Exception) { e.toPodcastProblem() }
                _state.update { s -> s.copy(tracking = if (problem == null || problem.kind == PodcastProblemKind.NOT_FOUND) s.tracking - id else s.tracking + (id to problem)) }
                if (problem != null && problem.kind != PodcastProblemKind.NOT_FOUND) worst = problem
                // Told to slow down or to sign in: the rest of this round would get the same answer.
                if (problem != null && (problem.kind == PodcastProblemKind.SLOW_DOWN || problem.needsSettings)) break
            }
            failures = if (worst == null) 0 else failures + 1
            val wait = trackWait(active.size, active.minOf { age[it] ?: 0L }, worst, failures)
            active.forEach { age[it] = (age[it] ?: 0L) + wait }
            withTimeoutOrNull(wait) { trackNow.receive() }
        }
    }

    companion object {
        const val LIST_EVERY_MS = 120_000L
        /** Requests per minute left to status checks; the gateway allows 60 per phone address for everything. */
        const val CHECKS_PER_MINUTE = 24

        /** How long to wait before reading the list again. */
        fun listWait(problem: PodcastProblem?, failures: Int): Long = when {
            problem == null -> LIST_EVERY_MS
            // Signing in or being given access re-creates the screen's sync; until then, asking often is pointless.
            problem.needsSettings || problem.kind == PodcastProblemKind.NOT_CONFIGURED -> 5 * 60_000L
            else -> maxOf(problem.retryAfterSec?.times(1000L) ?: 0L, if (problem.kind == PodcastProblemKind.SLOW_DOWN) 60_000L else 0L,
                (15_000L shl (failures - 1).coerceIn(0, 5)).coerceAtMost(5 * 60_000L))
        }

        /**
         * How long to wait between rounds of status checks for [count] podcasts, the newest of which has been
         * followed for [youngestMs].
         */
        fun trackWait(count: Int, youngestMs: Long, problem: PodcastProblem?, failures: Int): Long {
            val pace = when {
                youngestMs < 2 * 60_000L -> 4_000L
                youngestMs < 10 * 60_000L -> 8_000L
                youngestMs < 30 * 60_000L -> 15_000L
                else -> 60_000L
            }
            val budget = count.coerceAtLeast(1) * 60_000L / CHECKS_PER_MINUTE
            val base = maxOf(pace, budget)
            if (problem == null) return base
            if (problem.needsSettings) return 5 * 60_000L
            val backoff = (base shl failures.coerceIn(1, 4)).coerceAtMost(maxOf(base, 60_000L))
            return maxOf(backoff, problem.retryAfterSec?.times(1000L) ?: 0L, if (problem.kind == PodcastProblemKind.SLOW_DOWN) 60_000L else 0L)
        }
    }
}
