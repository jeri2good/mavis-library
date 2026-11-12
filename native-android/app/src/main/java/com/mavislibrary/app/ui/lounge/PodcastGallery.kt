package com.mavislibrary.app.ui.lounge

import com.mavislibrary.app.data.podcast.PodcastJob
import com.mavislibrary.app.data.podcast.PodcastProblem
import com.mavislibrary.app.data.podcast.PodcastProblemKind
import com.mavislibrary.app.data.podcast.PodcastSyncState
import com.mavislibrary.app.data.podcast.SavedPodcast
import com.mavislibrary.app.data.podcast.podcastStyles
import com.mavislibrary.app.ui.podcast.episodeSources
import com.mavislibrary.app.ui.podcast.episodeWhen
import java.time.Instant
import java.time.ZoneId

/** Gallery sections, in the order they are shown. */
enum class PodcastShelf(val label: String) {
    CONTINUE("Continue listening"),
    NEW("New"),
    MAKING("Being made"),
    FAILED("Couldn't be made"),
    PLAYED("Played"),
}

data class Episode(val saved: SavedPodcast, val positionMs: Long?) {
    val job get() = saved.job
    val shelf: PodcastShelf get() = shelfOf(job, positionMs)
    /** How much has been heard, 0..1, when the length is known. */
    val heard: Float? get() = job.durationSec?.takeIf { it > 0 }?.let { ((positionMs ?: 0) / (it * 1000f)).coerceIn(0f, 1f) }
}

/** A few seconds in counts as started; the last half minute counts as finished. */
fun shelfOf(job: PodcastJob, positionMs: Long?): PodcastShelf = when {
    job.status == "failed" -> PodcastShelf.FAILED
    !job.terminal -> PodcastShelf.MAKING
    positionMs == null || positionMs < 5_000 -> PodcastShelf.NEW
    job.durationSec != null && positionMs >= job.durationSec * 1000L - 30_000 -> PodcastShelf.PLAYED
    else -> PodcastShelf.CONTINUE
}

/** [saved] newest first; empty sections are left out. */
fun gallery(saved: List<SavedPodcast>, positions: Map<String, Long>): Map<PodcastShelf, List<Episode>> =
    saved.map { Episode(it, positions[it.resumeKey]) }.groupBy { it.shelf }.toSortedMap(compareBy { it.ordinal })

/**
 * What plays, starting with [first]: then the new episodes listed below it (all new ones when [first] isn't new),
 * so a morning's podcasts run back to back.
 */
fun upNext(first: Episode, shelves: Map<PodcastShelf, List<Episode>>): List<Episode> {
    val fresh = shelves[PodcastShelf.NEW].orEmpty()
    return listOf(first) + fresh.drop(fresh.indexOfFirst { it.saved.key == first.saved.key } + 1)
}

fun stageLabel(job: PodcastJob): String {
    val step = when (job.status) {
        "queued" -> "Waiting its turn"
        "sourcing" -> "Reading the sources"
        "writing" -> "Writing the script"
        "generating" -> "Recording the hosts"
        "ready" -> "Ready"
        "failed" -> "Couldn't be made"
        else -> job.status
    }
    return if (!job.terminal && job.progress >= 1) "$step · ${job.progress.toInt()}%" else step
}

fun minutesLabel(seconds: Int) = if (seconds < 60) "$seconds sec" else "${(seconds + 30) / 60} min"

/** When · sources · length (or time left) · style · Downloaded, matching the Up next cards. */
fun episodeDetails(episode: Episode, zone: ZoneId = ZoneId.systemDefault(), now: Instant = Instant.now()): String {
    val job = episode.job
    val left = job.durationSec?.takeIf { episode.shelf == PodcastShelf.CONTINUE }?.let { it - ((episode.positionMs ?: 0) / 1000).toInt() }
    return listOfNotNull(
        episodeWhen(job, now, zone).takeIf(String::isNotEmpty),
        episodeSources(job).takeIf(String::isNotEmpty),
        left?.let { "${minutesLabel(it.coerceAtLeast(0))} left" } ?: job.durationSec?.let(::minutesLabel),
        if (com.mavislibrary.app.data.podcast.episodeKind(job) == "Bible lesson") "Bible lesson" else podcastStyles[job.style],
        "Downloaded".takeIf { episode.saved.localFile != null },
    ).joinToString(" · ")
}

/** The status line at the top of the Lounge and what it allows. */
data class LoungeBanner(
    val text: String,
    /** Something is on its way: show a spinner. */
    val busy: Boolean = false,
    /** Nothing is known yet and nothing is cached: show placeholder rows instead of "no podcasts". */
    val skeleton: Boolean = false,
    val error: Boolean = false,
    /** A second line, e.g. what still works. */
    val detail: String? = null,
    /** Label for a button to Settings, when only Settings can fix it. */
    val settingsLabel: String? = null,
    val canCreate: Boolean = true,
)

/** First load, a later refresh, and each kind of failure read differently; cached podcasts stay usable throughout. */
fun loungeBanner(state: PodcastSyncState, hasEpisodes: Boolean, waking: Boolean): LoungeBanner {
    val problem = state.problem
    return when {
        waking -> LoungeBanner("Waking up the studio…", busy = true, skeleton = state.firstLoad && !hasEpisodes, canCreate = state.synced)
        state.firstLoad -> LoungeBanner(if (hasEpisodes) "Checking your studio for new podcasts…" else "Loading your podcasts…", busy = true, skeleton = !hasEpisodes, canCreate = false)
        problem == null -> LoungeBanner(if (state.loading) "Checking for new podcasts…" else "Studio connected", busy = state.loading)
        problem.needsSettings -> LoungeBanner(problem.message, error = true, settingsLabel = problem.settingsLabel, canCreate = false,
            detail = "Downloaded podcasts still play.".takeIf { hasEpisodes })
        else -> LoungeBanner(
            when (problem.kind) {
                PodcastProblemKind.OFFLINE -> "You're offline"
                PodcastProblemKind.STUDIO_DOWN -> "Studio not answering"
                PodcastProblemKind.STUDIO_BUSY -> "Studio busy"
                PodcastProblemKind.SLOW_DOWN -> "Slowing down for a minute"
                PodcastProblemKind.NOT_CONFIGURED -> "Podcast studio isn't set up yet"
                else -> "Couldn't update your podcasts"
            } + if (state.loading) " · trying again…" else "",
            busy = state.loading, error = !state.loading,
            detail = listOfNotNull(problem.message, if (hasEpisodes) "Downloaded podcasts still play." else null, if (problem.temporary) "Mavis keeps trying on its own." else null).joinToString(" "),
            // The list failing doesn't always mean a new podcast would: only block what certainly can't work.
            canCreate = problem.kind != PodcastProblemKind.NOT_CONFIGURED,
        )
    }
}

/** The line under a podcast being made when its last status check failed. */
fun trackingNote(problem: PodcastProblem): String = when (problem.kind) {
    PodcastProblemKind.OFFLINE -> "Can't check right now · you're offline · trying again"
    PodcastProblemKind.STUDIO_DOWN, PodcastProblemKind.STUDIO_BUSY -> "Studio not answering · trying again"
    PodcastProblemKind.SLOW_DOWN -> "Checking less often for a minute"
    PodcastProblemKind.SIGN_IN -> "Sign in again to follow this podcast"
    PodcastProblemKind.ACCESS -> "Podcast access is needed to follow this"
    else -> "Couldn't check this one · trying again"
}
