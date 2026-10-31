package com.mavislibrary.app.ui.podcast

import com.mavislibrary.app.data.podcast.PodcastJob
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val DATED_TITLE = Regex("^[A-Z][a-z]{2}, [A-Z][a-z]{2} \\d{1,2} · ")
private val TIME = DateTimeFormatter.ofPattern("h:mm a", Locale.US)
private val DAY = DateTimeFormatter.ofPattern("EEE, MMM d", Locale.US)

/** What the episode is about: the title without its "Thu, Oct 29 · " day, or old briefings named from their stories. */
fun episodeHeadline(job: PodcastJob): String {
    val title = job.title.trim()
    DATED_TITLE.find(title)?.let { prefix -> title.substring(prefix.range.last + 1).trim().takeIf(String::isNotEmpty)?.let { return it } }
    val stories = job.stories.map { it.title.trim() }.filter(String::isNotEmpty)
    if (title.equals("Daily briefing", ignoreCase = true) && stories.isNotEmpty()) {
        val heads = stories.take(2)
        return heads.joinToString(", ") + if (stories.size > heads.size) " + ${stories.size - heads.size} more" else ""
    }
    return title
}

/** "Today 6:44 AM", "Yesterday 6:44 AM", or "Thu, Oct 29"; empty without a readable createdAt. */
fun episodeWhen(job: PodcastJob, now: Instant = Instant.now(), zone: ZoneId = ZoneId.systemDefault()): String {
    val made = runCatching { Instant.parse(job.createdAt).atZone(zone) }.getOrNull() ?: return ""
    val today = now.atZone(zone).toLocalDate()
    return when (made.toLocalDate()) {
        today -> "Today ${TIME.format(made)}"
        today.minusDays(1) -> "Yesterday ${TIME.format(made)}"
        else -> DAY.format(made)
    }
}

/** Up to three site names, then "+N". */
fun episodeSources(job: PodcastJob): String {
    val names = job.sites.ifEmpty { job.stories.map { it.site } }.map(String::trim).filter(String::isNotEmpty).distinct()
    if (names.isEmpty()) return ""
    return names.take(3).joinToString(" · ") + if (names.size > 3) " +${names.size - 3}" else ""
}

fun episodeSubtitle(job: PodcastJob, now: Instant = Instant.now(), zone: ZoneId = ZoneId.systemDefault()): String =
    listOf(if (com.mavislibrary.app.data.podcast.episodeKind(job) == "Bible lesson") "Bible lesson" else "", episodeWhen(job, now, zone), episodeSources(job), job.durationSec?.let { "${maxOf(1, (it + 30) / 60)} min" }.orEmpty())
        .filter(String::isNotEmpty).joinToString(" · ")
