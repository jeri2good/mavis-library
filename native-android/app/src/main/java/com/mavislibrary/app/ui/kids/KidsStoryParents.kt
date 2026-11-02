package com.mavislibrary.app.ui.kids

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import com.mavislibrary.app.data.kids.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.roundToInt

@Composable
fun KidsStoryParents(repository: StoryRepository, prefs: KidsPrefs, state: KidsState, onLeave: () -> Unit) {
    val scope = rememberCoroutineScope()
    var age by remember(state.childAge) { mutableFloatStateOf(state.childAge.toFloat()) }
    var catalog by remember { mutableStateOf<StoryCatalog?>(null) }
    var credits by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var previewJob by remember { mutableStateOf<Job?>(null) }
    val context = androidx.compose.ui.platform.LocalContext.current
    val preview = remember { ExoPlayer.Builder(context).build() }
    DisposableEffect(Unit) { onDispose { previewJob?.cancel(); preview.release() } }
    LaunchedEffect(Unit) {
        catalog = withContext(Dispatchers.IO) { repository.cachedCatalog() }
        try { catalog = repository.catalog() }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) { message = "Catalog unavailable offline until its first download." }
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(if (credits) "Story credits" else "Grown-ups corner", fontSize = 30.sp)
        if (credits) {
            // Plain text intentionally: licensing URLs are not clickable in kids mode.
            KidsText(catalog?.credits ?: "Download the story catalog to see its credits.")
            KidsText("LibriVox recordings are in the public domain, read by volunteers. Audio hosted by the Internet Archive.")
            Button(onClick = { credits = false }) { KidsText("Back to settings") }
        } else {
            KidsText("Child's age: ${age.roundToInt()}")
            Slider(value = age, onValueChange = { age = it }, valueRange = 2f..10f, steps = 7,
                onValueChangeFinished = {
                    scope.launch {
                        try { prefs.storyAge(age.roundToInt()) }
                        catch (_: Exception) { message = "Couldn't save age. Please try again." }
                    }
                })
            KidsText("Narrator")
            catalog?.voicesFor(age.roundToInt()).orEmpty().forEach { voice ->
                val id = voice.id
                val label = voice.label
                FilterChip(selected = catalog?.narrator(age.roundToInt(), state.narrator.takeIf { state.narratorChosen }) == id, onClick = {
                    scope.launch {
                        try { prefs.storySettings(age.roundToInt(), id) }
                        catch (_: Exception) { message = "Couldn't save narrator. Please try again." }
                    }
                }, label = { KidsText(label) }, modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp))
                Button(onClick = {
                    previewJob?.cancel()
                    preview.stop()
                    previewJob = scope.launch {
                        message = "Loading preview..."
                        try {
                            val clip = repository.preview(id, catalog)
                            if (clip == null) message = "This preview isn't in the pack yet. Your narrator choice is still saved."
                            else {
                                preview.setMediaItem(MediaItem.fromUri(clip.toURI().toString()))
                                preview.prepare()
                                // Count the three seconds from actual playback, not from network/buffering time.
                                try {
                                    preview.play()
                                    val ready = withTimeoutOrNull(10_000) {
                                        while (!preview.isPlaying && preview.playerError == null) delay(50)
                                        preview.isPlaying
                                    } == true
                                    if (ready) { message = "Preview: $label"; delay(3000) }
                                    else message = "This preview couldn't play. Your narrator choice is still saved."
                                } finally { preview.pause() }
                            }
                        } catch (e: CancellationException) { throw e }
                        catch (_: Exception) { message = "Preview unavailable. Your narrator choice is still saved." }
                    }
                }, modifier = Modifier.heightIn(min = 56.dp)) { Text("Hear 3-second preview") }
            }
            message?.let { KidsText(it) }
            Button(onClick = { preview.stop(); credits = true }, modifier = Modifier.heightIn(min = 64.dp)) { KidsText("Story credits") }
            OutlinedButton(onClick = onLeave, modifier = Modifier.heightIn(min = 64.dp)) { KidsText("Leave Kids mode") }
        }
    }
}
