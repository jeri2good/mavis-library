package com.mavislibrary.app.ui.kids

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.mavislibrary.app.data.kids.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@Composable
fun KidsStoryShelf(repository: StoryRepository, age: Int, connected: Boolean, modifier: Modifier = Modifier,
                   onOpen: (StoryEntry) -> Unit) {
    var catalog by remember { mutableStateOf<StoryCatalog?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf<String?>(null) }
    var progress by remember { mutableFloatStateOf(0f) }
    var retry by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(retry) {
        error = null
        catalog = withContext(Dispatchers.IO) { repository.cachedCatalog() }
        try { catalog = repository.catalog() }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) { error = "Stories need the internet the first time. Try again when you're connected." }
    }
    BoxWithConstraints(modifier) {
        val columns = if (maxWidth < 600.dp) 2 else if (maxWidth < 840.dp) 3 else 4
        LazyVerticalGrid(GridCells.Fixed(columns), verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Column {
                    Text("Story shelf", fontSize = 32.sp)
                    KidsText("Little stories. Big wonder.")
                    error?.let { KidsText(it) }
                    if (catalog == null && error == null) CircularProgressIndicator(Modifier.padding(24.dp))
                    if (error != null) Button(onClick = { retry++ }) { KidsText("Try again") }
                }
            }
            items(catalog?.stories.orEmpty(), key = { it.id }) { entry ->
                var cover by remember(entry.id) { mutableStateOf<File?>(null) }
                var downloaded by remember(entry.id) { mutableStateOf(false) }
                LaunchedEffect(entry, busy) {
                    downloaded = withContext(Dispatchers.IO) { repository.installed(entry) }
                }
                LaunchedEffect(entry.cover) { cover = repository.cover(entry) }
                Card(onClick = {
                    if (busy == null) scope.launch {
                        busy = entry.id; progress = 0f; error = null
                        try {
                            repository.download(entry) { progress = it }
                            downloaded = true
                            onOpen(entry)
                        } catch (e: CancellationException) { throw e }
                        catch (_: Exception) { error = "That story couldn't download. Tap its cover to retry or resume." }
                        finally { busy = null }
                    }
                }, enabled = busy == null && connected, shape = RoundedCornerShape(28.dp)) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Box(Modifier.fillMaxWidth().aspectRatio(0.9f), contentAlignment = Alignment.Center) {
                            if (cover != null) AsyncImage(cover, entry.title, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                            else Text("Story", fontSize = 28.sp, color = MaterialTheme.colorScheme.primary)
                            if (busy == entry.id) Surface(shape = RoundedCornerShape(50), tonalElevation = 6.dp) {
                                CircularProgressIndicator(progress = { progress }, Modifier.padding(16.dp).size(56.dp), strokeWidth = 6.dp)
                            }
                        }
                        KidsText(entry.title)
                        Text("${(entry.minutes[storyTier(age)] ?: 4.0).let { kotlin.math.max(1, kotlin.math.round(it).toInt()) }} min" + if (downloaded) "  \u2713 Downloaded" else "",
                            fontSize = 16.sp)
                    }
                }
            }
        }
    }
}
