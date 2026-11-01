package com.mavislibrary.app.ui.lounge

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Bedtime
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.WbSunny
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mavislibrary.app.data.podcast.PodcastJob
import com.mavislibrary.app.data.podsub.SubscriptionSchedule
import com.mavislibrary.app.ui.podcast.episodeHeadline
import java.time.Instant
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/** One "Your Daily" card on Listen: the briefing, or one devotional rule. */
data class DailyCard(val id: String, val briefing: Boolean, val title: String, val time: String, val next: String, val playable: PodcastJob?)

fun readyByLabel(readyBy: String): String =
    runCatching { LocalTime.parse(readyBy).format(DateTimeFormatter.ofPattern("h:mm a", Locale.US)) }.getOrDefault(readyBy)

private fun nextLabel(at: String?, schedule: SubscriptionSchedule, now: Instant, enabled: Boolean = true): String {
    if (!enabled) return "Paused"
    val arrival = subscriptionArrival(at, schedule, now)
    return if (!schedule.enabled) arrival else "next: " + arrival.replaceFirstChar { it.lowercase() }
}

/**
 * The briefing card (when any site feeds the briefing) then one card per devotional rule. Play targets the
 * latest ready episode of that rule, from the library or today's subscription episodes.
 */
fun dailyCards(state: PodsubUiState, library: List<PodcastJob>, now: Instant = Instant.now()): List<DailyCard> {
    val doc = state.document ?: return emptyList()
    val known = library + state.episodes
    fun ready(id: String?) = id?.let { jobId -> known.firstOrNull { it.id == jobId && it.status == "ready" } }
    val briefingSites = doc.sites.filter { it.mode == "top" && it.delivery == "briefing" }.map { it.id }.toSet()
    val briefing = if (briefingSites.isEmpty()) null else {
        val hour = runCatching { LocalTime.parse(doc.schedule.readyBy).hour }.getOrDefault(6)
        val last = state.lastRun?.episodes?.lastOrNull { e -> e.siteIds.any { it in briefingSites } }
        DailyCard("briefing", true, when { hour < 12 -> "Morning Briefing"; hour >= 17 -> "Evening Briefing"; else -> "Daily Briefing" },
            readyByLabel(doc.schedule.readyBy), nextLabel(state.nextRunAt, doc.schedule, now), ready(last?.jobId))
    }
    return listOfNotNull(briefing) + doc.devotionals.map { d ->
        DailyCard("devotional_${d.id}", false, d.name, readyByLabel(doc.devotionalSchedule.readyBy),
            nextLabel(state.nextDevotionalAt, doc.devotionalSchedule, now, d.enabled), ready(state.lastDevotionals[d.id]?.jobId))
    }
}

/** "Listen" headline with the gear that opens Settings → Podcasts. */
@Composable
fun ListenTitle(onPodcastSettings: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("Listen", Modifier.weight(1f).semantics { heading() }, style = MaterialTheme.typography.headlineLarge)
        IconButton(onClick = onPodcastSettings, modifier = Modifier.size(48.dp).testTag("podcast_settings_gear")) {
            Icon(Icons.Outlined.Settings, contentDescription = "Podcast settings")
        }
    }
}

@Composable
fun SectionHeading(title: String, modifier: Modifier = Modifier, trailing: String? = null) {
    Row(modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, Modifier.weight(1f).semantics { heading() }, style = MaterialTheme.typography.headlineSmall)
        trailing?.let { Text(it, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

@Composable
fun YourDailyCard(card: DailyCard, enabled: Boolean, onOpen: () -> Unit, onPlay: (PodcastJob) -> Unit) {
    Surface(onClick = onOpen, shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth().testTag("daily_${card.id}")) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Box(Modifier.size(48.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceContainerHighest), contentAlignment = Alignment.Center) {
                Icon(if (card.briefing) Icons.Outlined.WbSunny else Icons.Outlined.Bedtime, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            }
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(card.title, Modifier.weight(1f, fill = false), style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.width(6.dp))
                    Text(card.time, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                }
                Text(card.next, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            card.playable?.let { job ->
                FilledTonalIconButton(onClick = { onPlay(job) }, enabled = enabled, modifier = Modifier.size(56.dp).testTag("daily_play_${card.id}")) {
                    Icon(Icons.Outlined.PlayArrow, contentDescription = "Play latest ${card.title}")
                }
            }
        }
    }
}

/** Top row on Listen: the most recent episode in progress, with time left and a tonal Play/Pause. */
@Composable
fun ContinueListeningRow(episode: Episode, playing: Boolean, enabled: Boolean, onPlay: () -> Unit) {
    val job = episode.job
    val left = job.durationSec?.let { it - ((episode.positionMs ?: 0) / 1000).toInt() }?.coerceAtLeast(0)
    Row(Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium).clickable(enabled = enabled, role = Role.Button, onClickLabel = "Resume", onClick = onPlay)
        .padding(vertical = 4.dp).testTag("continue_listening"), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Box(Modifier.size(56.dp).clip(CircleShape).background(Color(0xFF111111)), contentAlignment = Alignment.Center) {
            Box(Modifier.size(20.dp).clip(CircleShape).background(Color.hsv(recordHue(episode), .45f, .85f)))
        }
        Column(Modifier.weight(1f)) {
            Text("CONTINUE LISTENING", style = MaterialTheme.typography.labelMedium.copy(letterSpacing = 1.5.sp), color = MaterialTheme.colorScheme.primary)
            Text(episodeHeadline(job), style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            left?.let { Text("${minutesLabel(it)} left", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        FilledTonalIconButton(onClick = onPlay, enabled = enabled, modifier = Modifier.size(56.dp)) {
            Icon(if (playing) Icons.Outlined.Pause else Icons.Outlined.PlayArrow, contentDescription = if (playing) "Pause" else "Resume ${episodeHeadline(job)}")
        }
    }
}
