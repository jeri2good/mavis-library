package com.mavislibrary.app.ui.lounge

import com.mavislibrary.app.data.podcast.PodcastJob
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
        podcastStyles[job.style],
        "Downloaded".takeIf { episode.saved.localFile != null },
    ).joinToString(" · ")
}
