package com.mavislibrary.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.mavislibrary.app.data.share.EpubImports
import com.mavislibrary.app.data.share.NativeEpubText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun ImportedReader(id: String, title: String, imports: EpubImports, onBack: () -> Unit, onPodcast: ((com.mavislibrary.app.data.podcast.PodcastDraft) -> Unit)? = null) {
    var chapters by remember(id) { mutableStateOf<List<String>?>(null) }
    var page by remember(id) { mutableStateOf(0) }
    var failed by remember(id) { mutableStateOf(false) }
    LaunchedEffect(id) {
        chapters = withContext(Dispatchers.IO) {
            runCatching { imports.open(id)?.let(NativeEpubText::read) }.getOrNull()
        }
        failed = chapters == null
    }
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        TextButton(onClick = onBack) { Text("Back to shelf") }
        Text(title, style = MaterialTheme.typography.headlineSmall)
        Text("Basic offline text view. Images, layout, notes and reading progress aren't available for Android-shared EPUBs yet.", style = MaterialTheme.typography.bodySmall)
        val content = chapters
        when {
            failed -> Text("This EPUB couldn't be read here. Try another DRM-free EPUB.")
            content == null -> Text("Opening book…")
            else -> {
                if (onPodcast != null) {
                    var menu by remember { mutableStateOf(false) }
                    androidx.compose.foundation.layout.Box {
                        TextButton(onClick = { menu = true }) { Text("Make a podcast") }
                        androidx.compose.material3.DropdownMenu(menu, { menu = false }) {
                            androidx.compose.material3.DropdownMenuItem(text = { Text("This chapter") }, onClick = {
                                menu = false; onPodcast(com.mavislibrary.app.data.podcast.PodcastDraft.extracted("chapter", "$title · Chapter ${page + 1}", content[page]))
                            })
                            androidx.compose.material3.DropdownMenuItem(text = { Text("Whole book") }, onClick = {
                                menu = false; onPodcast(com.mavislibrary.app.data.podcast.PodcastDraft.extracted("book", title, content.joinToString("\n\n")))
                            })
                        }
                    }
                }
                Text("Chapter ${page + 1} of ${content.size}")
                Text(content[page], Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()), style = MaterialTheme.typography.bodyLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(onClick = { page-- }, enabled = page > 0) { Text("Previous") }
                    Button(onClick = { page++ }, enabled = page < content.lastIndex) { Text("Next") }
                }
            }
        }
    }
}
