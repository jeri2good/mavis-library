package com.mavislibrary.app.ui.kids

import android.os.Bundle
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.mavislibrary.app.audio.AudioFileStore
import com.mavislibrary.app.audio.KidsStoryController
import com.mavislibrary.app.audio.KidsStoryService
import com.mavislibrary.app.audio.KidsStoryState
import com.mavislibrary.app.data.kids.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun KidsLongListen(repository: StoryRepository, files: AudioFileStore, connected: Boolean, modifier: Modifier,
                   onPlay: (LongListenBook, Int?) -> Unit) {
    var catalog by remember { mutableStateOf<LongListenCatalog?>(null) }
    var selected by rememberSaveable { mutableStateOf<String?>(null) }
    var retry by remember { mutableIntStateOf(0) }
    var message by remember { mutableStateOf<String?>(null) }
    var downloading by remember { mutableStateOf<String?>(null) }
    var progress by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    LaunchedEffect(retry) {
        catalog = withContext(Dispatchers.IO) { repository.cachedLongListen() }
        try { catalog = repository.longListen(); message = null }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) { message = "Connect to the internet to load Long Listen, then try again." }
    }
    val book = catalog?.books?.find { it.id == selected }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        KidsText("Long Listen")
        KidsText("Audio-only adventures, ages 6+")
        message?.let { KidsText(it) }
        if (catalog == null) Button(onClick = { retry++ }) { KidsText("Try again") }
        if (book == null) {
            BoxWithConstraints(Modifier.weight(1f)) {
                LazyVerticalGrid(GridCells.Fixed(if (maxWidth >= 720.dp) 3 else 2),
                    horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(catalog?.books.orEmpty(), key = { it.id }) { entry ->
                        Card(onClick = { selected = entry.id }) {
                            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                AsyncImage(entry.cover, "${entry.title} cover", Modifier.fillMaxWidth().height(180.dp))
                                KidsText(entry.title)
                                Text("${entry.totaltime} | ${entry.ageHint}")
                            }
                        }
                    }
                }
            }
        } else {
            KidsText(book.title)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { selected = null }) { Text("All books") }
                Button(enabled = connected, onClick = { onPlay(book, null) }) { Text("Resume / Play") }
            }
            Button(enabled = downloading == null, onClick = {
                downloading = book.id
                scope.launch {
                    try {
                        files.download(book.version()) { done, total ->
                            scope.launch { progress = "Saved $done of $total chapters" }
                        }
                        message = "Saved offline: ${book.title}"
                    } catch (e: CancellationException) { throw e }
                    catch (_: Exception) { message = "Download stopped. Tap Save offline to retry; saved chapters are kept." }
                    finally { downloading = null }
                }
            }, modifier = Modifier.heightIn(min = 56.dp)) {
                Text(if (downloading == book.id) progress.ifBlank { "Saving..." } else "Save offline")
            }
            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                itemsIndexed(book.sections) { index, chapter ->
                    FilledTonalButton(enabled = connected, onClick = { onPlay(book, index) },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp)) {
                        Column { KidsText("${index + 1}. ${chapter.title}"); Text(chapter.readers.joinToString()) }
                    }
                }
            }
        }
    }
}

@Composable
fun KidsLongPlayer(state: KidsStoryState, controller: KidsStoryController, modifier: Modifier, onShelf: () -> Unit) {
    Column(modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        state.error?.let { KidsText(it) }
        if (state.loading) CircularProgressIndicator()
        state.longBook?.let { book ->
            AsyncImage(book.cover, "${book.title} cover", Modifier.fillMaxWidth().height(260.dp))
            KidsText(book.title)
            KidsText(book.sections.getOrNull(state.page)?.title.orEmpty())
            Text("Chapter ${state.page + 1} of ${book.sections.size} | ${state.positionMs / 60_000}:${(state.positionMs / 1000 % 60).toString().padStart(2, '0')}")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(enabled = state.page > 0, onClick = { controller.command(KidsStoryService.PREVIOUS) },
                    modifier = Modifier.weight(1f).heightIn(min = 64.dp), contentPadding = PaddingValues(8.dp)) { Text("Previous") }
                Button(onClick = { controller.command(KidsStoryService.TOGGLE) },
                    modifier = Modifier.weight(1f).heightIn(min = 64.dp), contentPadding = PaddingValues(8.dp)) {
                    KidsText(if (state.playing) "Pause" else if (state.ended) "Replay" else "Play")
                }
                OutlinedButton(enabled = state.page < book.sections.lastIndex,
                    onClick = { controller.command(KidsStoryService.NEXT) },
                    modifier = Modifier.weight(1f).heightIn(min = 64.dp), contentPadding = PaddingValues(8.dp)) { Text("Next") }
            }
            KidsText("Sleep timer")
            listOf(listOf(15, 30), listOf(45, 60), listOf(-1, 0)).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEach { minutes ->
                        FilterChip(selected = state.sleepMinutes == minutes, onClick = {
                            controller.command(KidsStoryService.SLEEP, Bundle().apply { putInt("minutes", minutes) })
                        }, label = { Text(when (minutes) { -1 -> "End of chapter"; 0 -> "Off"; else -> "$minutes min" }) },
                            modifier = Modifier.heightIn(min = 56.dp))
                    }
                }
            }
        }
        OutlinedButton(onClick = onShelf, modifier = Modifier.heightIn(min = 64.dp)) { KidsText("Back to Long Listen") }
    }
}
