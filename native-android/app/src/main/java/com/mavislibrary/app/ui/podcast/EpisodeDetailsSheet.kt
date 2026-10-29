package com.mavislibrary.app.ui.podcast

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.mavislibrary.app.data.podcast.PodcastJob
import com.mavislibrary.app.data.podcast.PodcastStory
import com.mavislibrary.app.ui.reader.ReaderTarget

/** Story taps open the in-app reader when [onOpenReader] is set, otherwise the link opens outside the app. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EpisodeDetailsSheet(job: PodcastJob, onDismiss: () -> Unit, onPlay: () -> Unit, onOpenReader: ((ReaderTarget) -> Unit)? = null) {
    val context = LocalContext.current
    fun open(story: PodcastStory, link: String) {
        onDismiss()
        if (onOpenReader != null) onOpenReader(ReaderTarget(story.title, link, story.site))
        else runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(link)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 24.dp).testTag("episode_details"),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Episode details", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            Text(episodeHeadline(job), style = MaterialTheme.typography.headlineSmall)
            episodeSubtitle(job).takeIf(String::isNotEmpty)?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (job.stories.isEmpty()) Text("Story list not saved for this episode.", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("episode_no_stories"))
            else {
                Text("In this episode", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                job.stories.forEachIndexed { index, story ->
                    val link = story.link?.takeIf { it.startsWith("https://", ignoreCase = true) }
                    Row(Modifier.fillMaxWidth().then(if (link != null) Modifier.clickable { open(story, link) } else Modifier)
                        .padding(vertical = 6.dp).testTag("episode_story_$index"), verticalAlignment = Alignment.Top) {
                        Text("${index + 1}.", style = MaterialTheme.typography.titleSmall, modifier = Modifier.width(28.dp))
                        Column(Modifier.weight(1f)) {
                            Text(story.title, style = MaterialTheme.typography.titleSmall,
                                color = if (link != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                            if (story.site.isNotBlank()) Text(story.site, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
            Button(onClick = { onDismiss(); onPlay() }, enabled = job.status == "ready",
                modifier = Modifier.fillMaxWidth().height(56.dp).testTag("episode_details_play")) {
                Icon(Icons.Outlined.PlayArrow, contentDescription = null); Spacer(Modifier.width(8.dp)); Text("Play")
            }
        }
    }
}
