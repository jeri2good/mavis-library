package com.mavislibrary.app.ui.listen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Bedtime
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Headphones
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Replay10
import androidx.compose.material.icons.outlined.Forward30
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock
import kotlin.math.sqrt
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.mavislibrary.app.AppContainer
import com.mavislibrary.app.audio.AudioCommands
import com.mavislibrary.app.audio.PlaybackController
import com.mavislibrary.app.data.api.LibriVoxVersion
import com.mavislibrary.app.data.model.Book
import com.mavislibrary.app.data.model.BookSource
import kotlinx.coroutines.launch

@Composable
fun ListenScreen(container: AppContainer, controller: PlaybackController, onFirstPlay: () -> Unit, voice: com.mavislibrary.app.voice.VoiceUi? = null, onLounge: (() -> Unit)? = null) {
    val playback by controller.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf("") }
    var books by remember { mutableStateOf<List<Book>>(emptyList()) }
    var selected by remember { mutableStateOf<Book?>(null) }
    var versions by remember { mutableStateOf<List<LibriVoxVersion>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var downloadProgress by remember { mutableStateOf<String?>(null) }
    ShakeToExtend(controller)

    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = 18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item { Spacer(Modifier.height(4.dp)); Text("Listen", style = MaterialTheme.typography.headlineLarge) }
        if (onLounge != null) item {
            Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp)) {
                Text("Podcast Lounge", style = MaterialTheme.typography.titleLarge)
                Text("Turn a book, chapter, article or topic into a two-host podcast.")
                Button(onClick = onLounge) { Text("Open Podcast Lounge") }
            } }
        }
        item { com.mavislibrary.app.voice.VoiceMic(voice) }
        if (playback.chapterCount > 0) item {
            NativePlayer(
                playback = playback,
                onToggle = controller::toggle,
                onSeek = controller::seekTo,
                onBack = { controller.seekBy(-15_000) },
                onForward = { controller.seekBy(30_000) },
                onSpeed = controller::speed,
                onSleep = controller::command,
            )
        }
        item {
            Text("Find a free human-read audiobook", style = MaterialTheme.typography.titleLarge)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(query, { query = it.take(120) }, label = { Text("Book title") }, singleLine = true, modifier = Modifier.weight(1f))
                Button(enabled = query.isNotBlank() && !busy, onClick = {
                    scope.launch {
                        busy = true; message = null; selected = null; versions = emptyList()
                        runCatching { container.searchRepository.search(BookSource.GUTENBERG, query).value.books }
                            .onSuccess { books = it }
                            .onFailure { message = it.message }
                        busy = false
                    }
                }) { Text("Search") }
            }
        }
        if (busy) item { Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { CircularProgressIndicator() } }
        message?.let { item { Text(it, color = MaterialTheme.colorScheme.error) } }
        selected?.let { book ->
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Cover(book.coverUrl, book.title)
                    Column(Modifier.weight(1f)) {
                        Text(book.title, style = MaterialTheme.typography.titleLarge)
                        Text(book.authorLine, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            itemsIndexed(versions) { _, version ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(version.readers.joinToString().ifBlank { "LibriVox volunteers" }, style = MaterialTheme.typography.titleMedium)
                        Text("${version.sections.size} chapters · ${formatTime(version.totalSecs * 1000)}")
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = {
                                onFirstPlay()
                                scope.launch {
                                    val resume = container.resumePositionStore.load(book.key)
                                    controller.play(book, version, container.audioFiles, resume)
                                }
                            }) { Icon(Icons.Outlined.PlayArrow, null); Text(" Listen") }
                            OutlinedButton(enabled = downloadProgress == null, onClick = {
                                scope.launch {
                                    downloadProgress = "Starting…"
                                    runCatching { container.audioFiles.download(version) { n, total -> downloadProgress = "Saving $n of $total" } }
                                        .onSuccess { downloadProgress = "Saved for offline" }
                                        .onFailure { downloadProgress = it.message }
                                }
                            }) { Icon(Icons.Outlined.Download, null); Text(" Save offline") }
                        }
                        downloadProgress?.let { Text(it, style = MaterialTheme.typography.labelMedium) }
                    }
                }
            }
        } ?: itemsIndexed(books) { _, book ->
            OutlinedButton(
                modifier = Modifier.fillMaxWidth(),
                onClick = {
                    scope.launch {
                        busy = true; message = null
                        runCatching { container.audiobookRepository.find(book.title, book.authorLine, book.sourceId.toIntOrNull()) }
                            .onSuccess { found -> selected = book; versions = found; if (found.isEmpty()) message = "No LibriVox recording found." }
                            .onFailure { message = it.message }
                        busy = false
                    }
                },
            ) { Text("${book.title} — ${book.authorLine}") }
        }
        if (selected != null && versions.isNotEmpty()) {
            item { Text("Chapters", style = MaterialTheme.typography.titleLarge) }
            itemsIndexed(versions.first().sections) { index, chapter ->
                OutlinedButton(onClick = { controller.chapter(index) }, modifier = Modifier.fillMaxWidth()) {
                    Text("${index + 1}. ${chapter.title}", modifier = Modifier.weight(1f))
                    Text(formatTime(chapter.secs * 1000))
                }
            }
        }
        item { Spacer(Modifier.height(12.dp)) }
    }
}

@Composable
private fun NativePlayer(
    playback: com.mavislibrary.app.audio.PlaybackUiState,
    onToggle: () -> Unit,
    onSeek: (Long) -> Unit,
    onBack: () -> Unit,
    onForward: () -> Unit,
    onSpeed: (Float) -> Unit,
    onSleep: (String) -> Unit,
) {
    var sleepOpen by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        if (playback.chapter.startsWith("Made with")) com.mavislibrary.app.ui.lounge.PodcastCover(playback.title)
        else Cover(playback.artwork, playback.title, Modifier.size(width = 150.dp, height = 210.dp))
        Text(playback.title, style = MaterialTheme.typography.headlineSmall)
        Text(playback.chapter, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Slider(
            value = playback.positionMs.toFloat().coerceIn(0f, playback.durationMs.coerceAtLeast(1).toFloat()),
            onValueChange = { onSeek(it.toLong()) },
            valueRange = 0f..playback.durationMs.coerceAtLeast(1).toFloat(),
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.Outlined.Replay10, "Back 15 seconds") }
            FilledIconButton(onClick = onToggle, modifier = Modifier.size(64.dp)) {
                Icon(if (playback.playing) Icons.Outlined.Pause else Icons.Outlined.PlayArrow, if (playback.playing) "Pause" else "Play")
            }
            IconButton(onClick = onForward) { Icon(Icons.Outlined.Forward30, "Forward 30 seconds") }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            listOf(.75f, 1f, 1.25f, 1.5f, 2f).forEach { speed ->
                OutlinedButton(
                    onClick = { onSpeed(speed) },
                    modifier = Modifier.weight(1f),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 2.dp),
                ) { Text(if (playback.speed == speed) "•${speed}×" else "${speed}×", maxLines = 1, softWrap = false) }
            }
        }
        Box {
            OutlinedButton(onClick = { sleepOpen = true }) { Icon(Icons.Outlined.Bedtime, null); Text("  Sleep timer") }
            DropdownMenu(sleepOpen, { sleepOpen = false }) {
                    listOf(15, 30, 45, 60).forEach { m -> DropdownMenuItem({ Text("$m minutes") }, { onSleep("mavis.sleep.$m"); sleepOpen = false }) }
                    DropdownMenuItem({ Text("End of chapter") }, { onSleep(AudioCommands.SLEEP_CHAPTER); sleepOpen = false })
                    DropdownMenuItem({ Text("Off") }, { onSleep(AudioCommands.SLEEP_CANCEL); sleepOpen = false })
                }
        }
        Text(playback.sleepLabel, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
    }
}

@Composable
private fun ShakeToExtend(controller: PlaybackController) {
    val context = LocalContext.current
    DisposableEffect(controller) {
        val manager = context.getSystemService(SensorManager::class.java)
        val accelerometer = manager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        var lastShake = 0L
        val listener = object : SensorEventListener {
            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
            override fun onSensorChanged(event: SensorEvent) {
                val g = sqrt(event.values.sumOf { (it * it).toDouble() }).toFloat() / SensorManager.GRAVITY_EARTH
                val now = SystemClock.elapsedRealtime()
                if (g > 2.5f && now - lastShake > 5_000) {
                    lastShake = now
                    controller.command(AudioCommands.SLEEP_EXTEND)
                }
            }
        }
        if (accelerometer != null) manager.registerListener(listener, accelerometer, SensorManager.SENSOR_DELAY_NORMAL)
        onDispose { manager?.unregisterListener(listener) }
    }
}

@Composable
fun MiniPlayer(controller: PlaybackController, modifier: Modifier = Modifier, voice: com.mavislibrary.app.voice.VoiceUi? = null) {
    val state by controller.state.collectAsStateWithLifecycle()
    if (state.chapterCount == 0) return
    Row(modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Outlined.Headphones, null)
        Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
            Text(state.title, maxLines = 1, style = MaterialTheme.typography.labelLarge)
            Text(state.chapter, maxLines = 1, style = MaterialTheme.typography.labelSmall)
        }
        com.mavislibrary.app.voice.VoiceMic(voice)
        IconButton(onClick = controller::toggle) { Icon(if (state.playing) Icons.Outlined.Pause else Icons.Outlined.PlayArrow, null) }
    }
}

@Composable
private fun Cover(url: String?, title: String, modifier: Modifier = Modifier.size(width = 84.dp, height = 116.dp)) {
    Card(modifier) {
        if (url != null) AsyncImage(url, title, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        else Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Icon(Icons.Outlined.Headphones, null, Modifier.size(42.dp)) }
    }
}

private fun formatTime(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}
