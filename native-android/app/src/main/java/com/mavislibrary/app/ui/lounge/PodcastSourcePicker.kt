package com.mavislibrary.app.ui.lounge

import android.webkit.WebView
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import coil3.compose.AsyncImage
import com.mavislibrary.app.AppContainer
import com.mavislibrary.app.data.model.Book
import com.mavislibrary.app.data.model.BookSource
import com.mavislibrary.app.data.podcast.PodcastDraft
import com.mavislibrary.app.data.share.*
import com.mavislibrary.app.data.feeds.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject

val sourceTypes = linkedMapOf("bible" to "Bible", "book" to "Book", "chapter" to "Chapter", "article" to "Website / Article", "topic" to "Topic")
@Composable
fun PodcastTypeCards(onChoose: (String) -> Unit) {
    sourceTypes.forEach { (kind, title) ->
        OutlinedCard(onClick = { onChoose(kind) }, modifier = Modifier.fillMaxWidth()) {
            Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Icon(when(kind) { "bible" -> Icons.Outlined.AutoStories; "book" -> Icons.Outlined.MenuBook; "chapter" -> Icons.Outlined.BookmarkBorder; "article" -> Icons.Outlined.Language; else -> Icons.Outlined.ChatBubbleOutline }, contentDescription = null)
                Column {
                    Text(title, style = MaterialTheme.typography.titleMedium)
                    Text(when(kind) { "bible" -> "A lesson from a Bible chapter"; "book" -> "Explore a book from your library"; "chapter" -> "Talk through one chapter"; "article" -> "Choose a story from your sites"; else -> "Tell the hosts what interests you" })
                }
            }
        }
    }
}
private data class BibleBook(val id: String, val name: String, val testament: String, val chapters: Int)
private data class PickerBook(val key: String, val title: String, val author: String, val cover: String?, val book: Book? = null, val imported: ImportedBook? = null)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun PodcastSourcePicker(kind: String, container: AppContainer, shelf: List<Book>, imported: List<ImportedBook>, webView: WebView?, onDismiss: () -> Unit, onPick: (PodcastDraft) -> Unit, onImported: (ImportedBook) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf("") }
    var annotations by remember { mutableStateOf(emptyList<PickerAnnotation>()) }
    var bible by remember { mutableStateOf(emptyList<BibleBook>()) }
    var bibleBook by remember { mutableStateOf<BibleBook?>(null) }
    var book by remember { mutableStateOf<PickerBook?>(null) }
    var chapters by remember { mutableStateOf(emptyList<PickerChapter>()) }
    var progress by remember { mutableStateOf(emptyMap<String, com.mavislibrary.app.data.db.ReadingProgressEntity>()) }
    var sites by remember { mutableStateOf(emptyList<FeedSource>()) }
    var site by remember { mutableStateOf<FeedSource?>(null) }
    var stories by remember { mutableStateOf(emptyList<FeedStory>()) }
    var read by remember { mutableStateOf(emptyMap<String, Set<String>>()) }
    var suggestions by remember { mutableStateOf(emptyList<String>()) }
    var classics by remember { mutableStateOf(false) }
    var results by remember { mutableStateOf(emptyList<Book>()) }
    var paste by remember { mutableStateOf(false) }
    var link by remember { mutableStateOf("") }
    var topic by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var webBooks by remember { mutableStateOf(emptyList<Pair<Book, Float?>>()) }
    val books = remember(shelf, imported, webBooks) {
        val native = shelf.map { PickerBook(it.key, it.title, it.authorLine, it.coverUrl, book = it) }
        val web = webBooks.filter { (b, _) -> native.none { it.key == b.key } }.map { (b, _) -> PickerBook(b.key, b.title, b.authorLine, b.coverUrl, book = b) }
        web + native + imported.map { PickerBook("import:${it.id}", it.title, "Imported book", null, imported = it) }
    }
    val webPercent = remember(webBooks) { webBooks.mapNotNull { (b, p) -> p?.let { b.key to it } }.toMap() }
    fun run(action: suspend () -> Unit) { scope.launch {
        loading = true; error = null
        try { action() } catch (e: CancellationException) { throw e } catch (e: Exception) { error = e.message ?: "Couldn't load this source. Try again." } finally { loading = false }
    } }
    LaunchedEffect(Unit) {
        launch { webBooks = parseWebBooks(pickerQuery(webView, "(async()=>{for(let i=0;i<100&&!window.MavisApp?.booksSummary;i++)await new Promise(r=>setTimeout(r,100));return window.MavisApp.booksSummary()})()", 12000)) }
        launch { annotations = parsePickerAnnotations(pickerQuery(webView, "(async()=>{for(let i=0;i<100&&!window.MavisApp?.annotationsSummary;i++)await new Promise(r=>setTimeout(r,100));return window.MavisApp.annotationsSummary()})()", 12000)) }
        launch { progress = books.mapNotNull { b -> container.database.readingProgress().get(b.key)?.let { b.key to it } }.toMap() }
        launch { sites = container.feedStore.sources.first(); read = container.readState.read.first(); suggestions = container.feedRepository.cachedStories().take(5).map { it.item.title } }
        if (kind == "bible") launch {
            bible = withContext(Dispatchers.IO) {
                val rows = JSONArray(JSONObject(context.assets.open("web/bible/index.json").bufferedReader().use { it.readText() }).getJSONArray("books").toString())
                (0 until rows.length()).map { rows.getJSONObject(it).let { b -> BibleBook(b.getString("id"), b.getString("name"), b.getString("testament"), b.getJSONArray("verses").length()) } }
            }
        }
    }
    fun pickBible(b: BibleBook, number: Int) = run {
        val draft = withContext(Dispatchers.IO) {
            val verses = JSONObject(context.assets.open("web/bible/kjv/${b.id}.json").bufferedReader().use { it.readText() }).getJSONArray("chapters").getJSONArray(number - 1)
            biblePickerDraft(b.name, number, (0 until verses.length()).map { verses.getString(it) })
        }
        onPick(draft)
    }
    fun pickBook(b: PickerBook) {
        if (kind == "book") { if (b.imported != null) { onDismiss(); onImported(b.imported) } else onPick(PodcastDraft.book(b.book!!)); return }
        book = b; query = ""; chapters = emptyList()
        run {
            chapters = if (b.imported != null) withContext(Dispatchers.IO) {
                val file = EpubImports(context).open(b.imported.id) ?: error("Import this book again to read its chapters.")
                NativeEpubText.read(file).mapIndexed { i, text -> PickerChapter("Chapter ${i + 1}", text) }
            } else {
                val marks = JSONArray().apply { annotations.filter { it.bookKey == b.key }.forEach { put(JSONObject().put("bookKey", it.bookKey).put("cfi", it.cfi)) } }
                val raw = pickerQuery(webView, "window.MavisApp?.podcastChapters?.(${JSONObject.quote(b.key)}, $marks, ${JSONObject.quote(progress[b.key]?.locator)})", 25000)
                    ?: error("Open and download this book in the reader, then return to choose a chapter.")
                val rows = JSONArray(raw)
                (0 until rows.length()).map { rows.getJSONObject(it).let { row -> PickerChapter(row.getString("label"), row.getString("text"), row.optBoolean("lastRead"), row.optBoolean("marked")) } }
            }
        }
    }
    fun matches(text: String) = text.contains(query.trim(), ignoreCase = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        LazyColumn(Modifier.fillMaxSize().padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(bottom = 32.dp)) {
            item {
                Text(sourceTypes[kind].orEmpty(), style = MaterialTheme.typography.headlineSmall)
                OutlinedTextField(query, { query = it.take(120) }, label = { Text("Search (optional)") }, modifier = Modifier.fillMaxWidth())
                if (bibleBook != null || book != null || site != null || classics || paste) TextButton(onClick = { bibleBook = null; book = null; site = null; classics = false; paste = false; query = "" }) { Text("Back to sources") }
                if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
            when (kind) {
                "bible" -> if (bibleBook != null) {
                    item { Text(bibleBook!!.name); FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        (1..bibleBook!!.chapters).filter { matches(it.toString()) || matches("${bibleBook!!.name} $it") }.forEach { n ->
                            OutlinedButton(onClick = { pickBible(bibleBook!!, n) }, modifier = Modifier.semantics { contentDescription = "${bibleBook!!.name} chapter $n" }) { Text("$n") }
                        }
                    } }
                } else {
                    item { Text("From your notes & highlights", style = MaterialTheme.typography.titleMedium) }
                    val notes = groupedBibleAnnotations(annotations)
                    if (notes.isEmpty()) item { Text("Highlight a verse in the Bible and it will show here.") }
                    notes.forEach { (_, group) ->
                        val parts = group.first().cfi.split('.'); val b = bible.find { it.id == parts[0] }; val n = parts.getOrNull(1)?.toIntOrNull()
                        if (b != null && n != null) {
                            item { Text("${b.name} $n", style = MaterialTheme.typography.titleSmall) }
                            items(group.filter { matches("${b.name} $n ${it.text} ${it.note}") }) { a ->
                                PickerRow("${b.name} ${a.cfi.substringAfter('.').replace('.', ':')}", "${a.text}\n${a.note}", badge = mapOf("note" to "Note", "quote" to "Saved verse", "highlight" to "Highlight", "wordhl" to "Word highlight")[a.kind] ?: a.kind) { pickBible(b, n) }
                            }
                        }
                    }
                    item { Text("Any chapter", style = MaterialTheme.typography.titleMedium) }
                    listOf("OT" to "Old Testament", "NT" to "New Testament").forEach { (testament, title) ->
                        item { Text(title) }
                        items(bible.filter { it.testament == testament && matches(it.name) }) { b -> PickerRow(b.name, "${b.chapters} chapters") { bibleBook = b; query = "" } }
                    }
                }
                "book", "chapter" -> if (book != null) {
                    item { Text(book!!.title) }
                    val rows = sortedPickerChapters(chapters.map { c -> c.copy(marked = c.marked || annotations.any { it.bookKey == book!!.key && it.chapter == c.label }) })
                    rows.filter { matches(it.label) }.groupBy { if (it.lastRead) "Last read" else if (it.marked) "Your marked chapters" else "Chapters" }.forEach { (title, group) ->
                        item { Text(title, style = MaterialTheme.typography.titleMedium) }
                        items(group) { c -> PickerRow(c.label, "${c.text.length} characters", badge = if (c.marked) "Marked" else null) { onPick(PodcastDraft.chapter(book!!.title, c.label, c.text)) } }
                    }
                    if (chapters.isEmpty() && !loading) item { Text("Open this book in the reader to make its chapters available.") }
                } else if (classics) {
                    item { Button(enabled = query.isNotBlank() && !loading, onClick = { run { results = container.searchRepository.search(BookSource.GUTENBERG, query).value.books } }) { Text("Search free classics") } }
                    items(results) { b -> PickerRow(b.title, b.authorLine, b.coverUrl) { pickBook(PickerBook(b.key, b.title, b.authorLine, b.coverUrl, b)) } }
                } else {
                    if (books.isEmpty()) item { Text("Save a book to your shelf or import an EPUB to see it here.") }
                    continueReadingFirst(books) { progress.containsKey(it.key) || webPercent.containsKey(it.key) }.filter { matches("${it.title} ${it.author}") }.groupBy { if (progress.containsKey(it.key) || webPercent.containsKey(it.key)) "Continue reading" else "Your books" }.forEach { (title, group) ->
                        item { Text(title, style = MaterialTheme.typography.titleMedium) }
                        items(group) { b -> PickerRow(b.title, "${b.author} · ${(((progress[b.key]?.fraction) ?: webPercent[b.key] ?: 0f) * 100).toInt()}%", b.cover) { pickBook(b) } }
                    }
                    item { PickerRow("Search free classics", "Find a Gutenberg book") { classics = true } }
                }
                "article" -> if (paste) {
                    item { OutlinedTextField(link, { link = it }, label = { Text("https://…") }); Button(enabled = link.isNotBlank(), onClick = { onPick(PodcastDraft.article(link, link)) }) { Text("Use link") } }
                } else if (site != null) {
                    item { Text(site!!.name) }
                    items(stories.filter { matches(it.item.title) }) { story ->
                        val unread = story.item.id !in read[site!!.id].orEmpty()
                        PickerRow(story.item.title, story.published?.let { java.text.DateFormat.getDateInstance().format(java.util.Date(it)) }.orEmpty(), badge = if (unread) "● Unread" else "Read") { onPick(PodcastDraft.article(story.item.title, story.item.url)) }
                    }
                    if (stories.isEmpty() && !loading) item { Text("No articles yet. Visit this site in Sites to load its latest stories.") }
                    item { PickerRow("Paste a link", "Use another website or article") { paste = true } }
                } else {
                    if (sites.isEmpty()) item { Text("Follow a website in Sites and its articles will appear here.") }
                    items(sites.filter { matches(it.name) }) { s -> PickerRow(s.name, s.siteUrl) { site = s; query = ""; stories = emptyList(); run { val loaded = container.feedRepository.load(listOf(s.copy(enabled = true))); stories = loaded.stories; error = loaded.note } } }
                    item { PickerRow("Paste a link", "Use another website or article") { paste = true } }
                }
                "topic" -> item {
                    OutlinedTextField(topic, { topic = it }, label = { Text("What should the hosts talk about?") }, modifier = Modifier.fillMaxWidth(), minLines = 3)
                    suggestions.filter(::matches).forEach { title -> SuggestionChip(onClick = { topic = title }, label = { Text(title) }) }
                    Button(enabled = topic.isNotBlank(), onClick = { onPick(PodcastDraft("topic", text = topic)) }) { Text("Use topic") }
                }
            }
        }
    }
}

@Composable
private fun PickerRow(title: String, subtitle: String, cover: String? = null, badge: String? = null, onClick: () -> Unit) {
    OutlinedCard(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (cover != null) AsyncImage(cover, contentDescription = "Cover of $title", modifier = Modifier.size(44.dp, 64.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, maxLines = 3)
                badge?.let { SuggestionChip(onClick = onClick, label = { Text(it) }) }
            }
        }
    }
}


/** Parses MavisApp.booksSummary() JSON into shelf books (+ reading fraction). Malformed rows are skipped. */
internal fun parseWebBooks(raw: String?): List<Pair<Book, Float?>> = runCatching {
    val arr = JSONArray(raw ?: return emptyList())
    (0 until arr.length()).mapNotNull { i ->
        val o = arr.optJSONObject(i) ?: return@mapNotNull null
        val key = o.optString("key").takeIf { it.isNotBlank() } ?: return@mapNotNull null
        val source = if (o.optString("source").equals("openlibrary", true) || key.startsWith("openlibrary:")) com.mavislibrary.app.data.model.BookSource.OPEN_LIBRARY else com.mavislibrary.app.data.model.BookSource.GUTENBERG
        val authors = o.optJSONArray("authors")?.let { a -> (0 until a.length()).map { a.optString(it) } } ?: emptyList()
        Book(key = key, source = source, sourceId = o.optString("sourceId").ifBlank { key.substringAfter(':') }, title = o.optString("title", "Untitled"),
            authors = authors, coverUrl = o.optString("coverUrl").takeIf { it.isNotBlank() && it != "null" }, formats = emptyList(), year = null,
            languages = emptyList(), epubUrl = null, sourceUrl = null) to (if (o.isNull("percent")) null else o.optDouble("percent").toFloat())
    }
}.getOrDefault(emptyList())
