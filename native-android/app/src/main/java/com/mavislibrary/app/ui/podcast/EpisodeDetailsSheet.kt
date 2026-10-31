package com.mavislibrary.app.ui.podcast

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.mavislibrary.app.BuildConfig
import com.mavislibrary.app.MavisApp
import com.mavislibrary.app.data.podcast.*
import com.mavislibrary.app.ui.reader.ReaderTarget
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** Story taps open the in-app reader when [onOpenReader] is set, otherwise the link opens outside the app. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EpisodeDetailsSheet(
    job: PodcastJob,
    onDismiss: () -> Unit,
    onPlay: () -> Unit,
    onOpenReader: ((ReaderTarget) -> Unit)? = null,
    onSaveMp3: (() -> Unit)? = null,
    onShare: (() -> Unit)? = null,
    actionsEnabled: Boolean = true,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var working by remember { mutableStateOf(false) }
    var progressLabel by remember { mutableStateOf<String?>(null) }
    var progressFraction by remember { mutableStateOf<Float?>(null) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    fun open(story: PodcastStory, link: String) {
        onDismiss()
        if (onOpenReader != null) onOpenReader(ReaderTarget(story.title, link, story.site))
        else runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(link)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }

    suspend fun resolveSaved(app: MavisApp): SavedPodcast =
        app.container.podcastSettings.library.first()
            .firstOrNull { it.job.id == job.id && (it.studio == BuildConfig.API_BASE || it.localFile != null) }
            ?.copy(job = job)
            ?: SavedPodcast(job, BuildConfig.API_BASE)

    fun fallbackSave() {
        val app = context.applicationContext as? MavisApp ?: return
        if (working) return
        scope.launch {
            working = true
            errorMessage = null
            progressLabel = "Saving MP3…"
            progressFraction = null
            var savedMp3: SavedPodcastMp3? = null
            try {
                val saved = resolveSaved(app)
                val source = app.container.podcastApi().asAudioSource().takeIf { saved.studio == BuildConfig.API_BASE }
                savedMp3 = savePodcastMp3(
                    context = context,
                    saved = saved,
                    audioSource = source,
                    localLookup = { app.container.audioFiles.podcastLocal(it.studio, it.job.id) },
                    onProgress = { written, total ->
                        progressLabel = formatExportProgressLabel("Saving MP3…", written, total)
                        progressFraction = if (total > 0L) (written.toFloat() / total.toFloat()).coerceIn(0f, 1f) else null
                    },
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                errorMessage = e.message ?: "Couldn't save MP3."
            } finally {
                progressLabel = null
                progressFraction = null
                working = false
            }
            if (savedMp3 != null) {
                val action = snackbar.showSnackbar(
                    message = SAVED_MP3_SNACKBAR,
                    actionLabel = "Open",
                    duration = SnackbarDuration.Long,
                )
                if (action == SnackbarResult.ActionPerformed) {
                    openSavedPodcastMp3(context, savedMp3.uri)
                }
            }
        }
    }

    fun fallbackShare() {
        val app = context.applicationContext as? MavisApp ?: return
        if (working) return
        scope.launch {
            working = true
            errorMessage = null
            progressLabel = "Preparing MP3…"
            progressFraction = null
            var shared = false
            try {
                val saved = resolveSaved(app)
                val source = app.container.podcastApi().asAudioSource().takeIf { saved.studio == BuildConfig.API_BASE }
                sharePodcastMp3(
                    context = context,
                    saved = saved,
                    audioSource = source,
                    localLookup = { app.container.audioFiles.podcastLocal(it.studio, it.job.id) },
                    onProgress = { written, total ->
                        progressLabel = formatExportProgressLabel("Preparing MP3…", written, total)
                        progressFraction = if (total > 0L) (written.toFloat() / total.toFloat()).coerceIn(0f, 1f) else null
                    },
                )
                shared = true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                errorMessage = e.message ?: "Couldn't share MP3."
            } finally {
                progressLabel = null
                progressFraction = null
                working = false
            }
            if (shared) onDismiss()
        }
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
            progressLabel?.let { label ->
                Column(Modifier.fillMaxWidth().testTag("episode_details_export_progress"), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                    val fraction = progressFraction
                    if (fraction != null) LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
                    else LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            }
            errorMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
            val ready = job.status == "ready"
            val canAct = ready && actionsEnabled && !working
            Button(onClick = { onDismiss(); onPlay() }, enabled = canAct,
                modifier = Modifier.fillMaxWidth().height(56.dp).testTag("episode_details_play")) {
                Icon(Icons.Outlined.PlayArrow, contentDescription = null); Spacer(Modifier.width(8.dp)); Text("Play")
            }
            if (ready) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(
                        onClick = { if (onSaveMp3 != null) { onDismiss(); onSaveMp3() } else fallbackSave() },
                        enabled = canAct,
                        modifier = Modifier.weight(1f).height(48.dp).testTag("episode_details_save_mp3"),
                    ) {
                        Icon(Icons.Outlined.Download, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text("Save MP3")
                    }
                    OutlinedButton(
                        onClick = { if (onShare != null) { onDismiss(); onShare() } else fallbackShare() },
                        enabled = canAct,
                        modifier = Modifier.weight(1f).height(48.dp).testTag("episode_details_share"),
                    ) {
                        Icon(Icons.Outlined.Share, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text("Share")
                    }
                }
            }
            SnackbarHost(hostState = snackbar)
        }
    }
}
