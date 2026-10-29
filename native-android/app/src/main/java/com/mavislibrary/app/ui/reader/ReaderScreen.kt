package com.mavislibrary.app.ui.reader

import android.content.ActivityNotFoundException
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.FormatSize
import androidx.compose.material.icons.outlined.Headphones
import androidx.compose.material.icons.outlined.OpenInBrowser
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import coil3.compose.AsyncImage
import com.mavislibrary.app.AppContainer
import com.mavislibrary.app.data.kids.kidsPrefs
import com.mavislibrary.app.data.podcast.PodcastDraft
import com.mavislibrary.app.data.podcast.loungeRouteAllowed
import com.mavislibrary.app.data.reader.ReaderArticle
import com.mavislibrary.app.data.reader.ReaderSettings
import com.mavislibrary.app.data.reader.ReaderTextSize
import com.mavislibrary.app.data.reader.ReaderTheme
import com.mavislibrary.app.ui.ArticleScreen

private data class ReaderColors(val background: Color, val text: Color, val muted: Color)
private fun ReaderTheme.colors() = when (this) {
    ReaderTheme.LIGHT -> ReaderColors(Color(0xFFFFFFFF), Color(0xFF1C1B1F), Color(0xFF5F5B66))
    ReaderTheme.SEPIA -> ReaderColors(Color(0xFFF4ECD8), Color(0xFF433422), Color(0xFF7A6650))
    ReaderTheme.DARK -> ReaderColors(Color(0xFF121212), Color(0xFFE6E1D6), Color(0xFFA8A29A))
}

/** Native reader for an article; falls back to the original page in the WebView when it can't be extracted. */
@Composable
fun ReaderScreen(
    container: AppContainer,
    target: ReaderTarget,
    onBack: () -> Unit,
    onPodcast: (PodcastDraft) -> Unit,
    onOpenOriginal: (PodcastDraft) -> Unit,
) {
    val context = LocalContext.current
    val kids by remember { context.kidsPrefs() }.state.collectAsStateWithLifecycle(initialValue = null)
    if (!loungeRouteAllowed(kids?.enabled)) return
    val vm: ReaderViewModel = viewModel(key = "reader-${target.url}", factory = viewModelFactory {
        initializer { ReaderViewModel(target, container.readerApi(), container.readerPrefs, container.readState) }
    })
    val state by vm.state.collectAsStateWithLifecycle()
    when (val content = state.content) {
        ReaderContent.Loading -> Column(Modifier.fillMaxSize().padding(16.dp).testTag("reader_loading")) {
            TextButton(onClick = onBack) { Text("Back") }
            LinearProgressIndicator(Modifier.fillMaxWidth())
            Spacer(Modifier.height(8.dp))
            Text(target.title, style = MaterialTheme.typography.titleMedium)
        }
        ReaderContent.Original -> Column(Modifier.fillMaxSize()) {
            Text("Showing original page", Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.secondaryContainer)
                .padding(horizontal = 16.dp, vertical = 6.dp).testTag("reader_fallback_note"),
                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSecondaryContainer)
            Box(Modifier.weight(1f)) { ArticleScreen(target.draft, onBack = onBack, onPodcast = onPodcast) }
        }
        is ReaderContent.Ready -> ReaderBody(content.article, target, state.settings, vm, onBack, onPodcast, onOpenOriginal)
    }
}

@Composable
private fun ReaderBody(
    article: ReaderArticle,
    target: ReaderTarget,
    settings: ReaderSettings,
    vm: ReaderViewModel,
    onBack: () -> Unit,
    onPodcast: (PodcastDraft) -> Unit,
    onOpenOriginal: (PodcastDraft) -> Unit,
) {
    val context = LocalContext.current
    val colors = settings.theme.colors()
    val title = article.title.ifBlank { target.title }
    val draft = PodcastDraft.article(title, article.url.ifBlank { target.url })
    var sheet by remember { mutableStateOf(false) }
    fun share() {
        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_SUBJECT, title).putExtra(Intent.EXTRA_TEXT, draft.url)
        try { context.startActivity(Intent.createChooser(send, "Share article")) } catch (_: ActivityNotFoundException) { }
    }
    Column(Modifier.fillMaxSize().background(colors.background).testTag("native_reader")) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back", tint = colors.text) }
            Spacer(Modifier.weight(1f))
            IconButton(onClick = { onPodcast(draft) }, modifier = Modifier.testTag("reader_listen")) { Icon(Icons.Outlined.Headphones, "Listen", tint = colors.text) }
            IconButton(onClick = ::share, modifier = Modifier.testTag("reader_share")) { Icon(Icons.Outlined.Share, "Share", tint = colors.text) }
            IconButton(onClick = { onOpenOriginal(draft) }, modifier = Modifier.testTag("reader_original")) { Icon(Icons.Outlined.OpenInBrowser, "Original page", tint = colors.text) }
            IconButton(onClick = { sheet = true }, modifier = Modifier.testTag("reader_text_options")) { Icon(Icons.Outlined.FormatSize, "Text size and theme", tint = colors.text) }
        }
        val body = settings.size.sp
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 32.dp)) {
            article.image?.let { url ->
                item {
                    AsyncImage(url, null, Modifier.fillMaxWidth().heightIn(max = 240.dp).clip(RoundedCornerShape(12.dp)), contentScale = ContentScale.Crop)
                    Spacer(Modifier.height(16.dp))
                }
            }
            item {
                Text(title, color = colors.text, fontFamily = FontFamily.Serif, fontWeight = FontWeight.Bold,
                    fontSize = (body + 8).sp, lineHeight = (body + 14).sp)
                Spacer(Modifier.height(8.dp))
                val meta = listOfNotNull(article.siteName?.takeIf(String::isNotBlank) ?: target.siteName.takeIf(String::isNotBlank),
                    article.byline?.takeIf(String::isNotBlank), "${article.readMin} min read")
                Text(meta.joinToString(" · "), color = colors.muted, style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.height(20.dp))
            }
            items(article.paragraphs) { paragraph ->
                Text(paragraph, color = colors.text, fontFamily = FontFamily.Serif, fontSize = body.sp, lineHeight = (body * 1.6f).sp,
                    modifier = Modifier.padding(bottom = (body * 0.9f).dp))
            }
        }
    }
    if (sheet) ReaderOptionsSheet(settings, vm) { sheet = false }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReaderOptionsSheet(settings: ReaderSettings, vm: ReaderViewModel, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Text size", style = MaterialTheme.typography.titleSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ReaderTextSize.entries.forEach { size ->
                    FilterChip(selected = settings.size == size, onClick = { vm.setSize(size) }, label = { Text(size.label) },
                        modifier = Modifier.weight(1f).testTag("reader_size_${size.name}"))
                }
            }
            Text("Theme", style = MaterialTheme.typography.titleSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ReaderTheme.entries.forEach { theme ->
                    FilterChip(selected = settings.theme == theme, onClick = { vm.setTheme(theme) }, label = { Text(theme.label) },
                        modifier = Modifier.weight(1f).testTag("reader_theme_${theme.name}"))
                }
            }
        }
    }
}
