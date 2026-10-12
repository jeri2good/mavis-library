package com.mavislibrary.app.ui.lounge

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mavislibrary.app.AppContainer
import com.mavislibrary.app.audio.PlaybackController
import com.mavislibrary.app.data.kids.kidsPrefs
import com.mavislibrary.app.data.model.Book
import com.mavislibrary.app.data.model.BookSource
import com.mavislibrary.app.data.podcast.*
import com.mavislibrary.app.data.share.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.catch

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PodcastLoungeScreen(container: AppContainer, playback: PlaybackController, initial: PodcastDraft?, onSettings: () -> Unit, onBack: () -> Unit, onFirstPlay: () -> Unit) {
    val context = LocalContext.current
    val kids by remember { context.kidsPrefs() }.state.collectAsStateWithLifecycle(initialValue = null)
    if (!loungeRouteAllowed(kids?.enabled)) return
    val prefs by container.podcastSettings.preferences.collectAsStateWithLifecycle(initialValue = null)
    val signedIn by container.accounts.signedIn.collectAsStateWithLifecycle(initialValue = null)
    val owner by container.secureStore.has(com.mavislibrary.app.data.secure.Secret.OWNER_ACCESS_CODE).collectAsStateWithLifecycle(initialValue = null)
    if (signedIn == null || owner == null) return
    if (signedIn != true && owner != true) {
        Column(Modifier.fillMaxSize().padding(16.dp).testTag("podcast_lounge"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            TextButton(onClick = onBack) { Text("Back to Listen") }
            Text("Podcast Lounge", style = MaterialTheme.typography.headlineLarge)
            Text("Sign in to use Podcast Lounge")
            Button(onClick = onSettings) { Text("Sign in in Settings") }
        }
        return
    }
    val loaded = (prefs ?: return).let { it.copy(address = com.mavislibrary.app.BuildConfig.API_BASE, style = it.style.takeIf { s -> s in podcastStyles } ?: "deep-dive") }
    val all by container.podcastSettings.library.collectAsStateWithLifecycle(initialValue = emptyList())
    val shelf by container.shelfRepository.shelfBooks.collectAsStateWithLifecycle(initialValue = emptyList())
    val imports = remember { EpubImports(context) }
    val imported by imports.books.collectAsStateWithLifecycle(initialValue = emptyList())
    val scope = rememberCoroutineScope()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val accountId by container.secureStore.observe(com.mavislibrary.app.data.secure.Secret.ACCOUNT_USER_ID).collectAsStateWithLifecycle(initialValue = null)
    val identity = if (owner == true) "owner" else accountId
    val api = remember(identity) { container.podcastApi() }
    val repo = remember(identity) { container.podcastRepository() }
    val wakingStudio by repo.wakingStudio.collectAsStateWithLifecycle(initialValue = false)
    var draft by remember(initial) { mutableStateOf(initial ?: PodcastDraft()) }
    var style by remember { mutableStateOf(loaded.style) }
    var length by remember { mutableStateOf(loaded.length) }
    var engine by remember { mutableStateOf(loaded.engine) }
    var accessReady by remember(signedIn, owner) { mutableStateOf(false) }
    var status by remember { mutableStateOf("Checking gateway…") }
    var message by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<Book>>(emptyList()) }
    var editingSource by remember(initial) { mutableStateOf(false) }
    var refresh by remember { mutableIntStateOf(0) }
    val pollingMessages = remember(loaded.address) { mutableStateMapOf<String, String>() }
    val active = remember(loaded.address) { mutableMapOf<String, Job>() }
    var delete by remember { mutableStateOf<SavedPodcast?>(null) }
    var sharedConfirm by remember(initial) { mutableStateOf(initial?.confirmShare == true) }
    val jobs = all.filter { (identity == "owner" || identity != null && it.accountId == identity) && (it.studio == loaded.address || it.localFile != null) && !podcastExpired(it.job, loaded.cleanupDays) }.sortedByDescending { it.job.createdAt }

    suspend fun allowed() = loungeRouteAllowed(context.kidsPrefs().state.first().enabled)
    suspend fun refreshStudio() {
        try {
            if (!allowed()) return
            repo.refresh()
            accessReady = true; status = "Gateway connected"; message = null
        } catch (e: CancellationException) { throw e } catch (e: Exception) {
            accessReady = false
            status = "Gateway unavailable"
            message = e.message ?: "Studio unavailable, try later"
        }
    }
    LaunchedEffect(loaded.address, identity, refresh, lifecycle) {
        if (loaded.address.isBlank()) return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            try { coroutineScope {
                launch { while (isActive) { refreshStudio(); delay(30_000) } }
                launch {
                    container.podcastSettings.library.collect { list ->
                        list.filter { (identity == "owner" || identity != null && it.accountId == identity) && it.studio == loaded.address && !it.job.terminal }.forEach { saved ->
                            if (saved.job.id !in active) active[saved.job.id] = launch {
                                repo.poll(saved.job.id).catch { pollingMessages[saved.job.id] = it.message ?: "Couldn't track this job. Refresh to retry." }.collect { event ->
                                    pollingMessages[saved.job.id] = when (event) {
                                        is PodcastPoll.Updated -> ""
                                        PodcastPoll.Unreachable -> "Studio unreachable · reconnecting with backoff"
                                        PodcastPoll.TimedOut -> "25-minute wait ended. The server job may still finish; tap Refresh to check."
                                    }
                                }
                            }
                        }
                    }
                }
            } } finally { active.values.forEach { it.cancel() }; active.clear() }
        }
    }
    LaunchedEffect(loaded.cleanupDays) {
        try { if (allowed()) container.audioFiles.cleanupPodcasts(container.podcastSettings, loaded.cleanupDays) }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) { message = "Local cleanup couldn't finish. Your studio originals are unchanged." }
    }
    DisposableEffect(loaded.address, identity, refresh) { onDispose { active.values.forEach { it.cancel() }; active.clear() } }
    fun act(action: suspend () -> Unit) {
        scope.launch {
            if (busy || !allowed()) return@launch
            busy = true; message = null
            try { action() } catch (e: CancellationException) { throw e } catch (e: Exception) { message = e.message ?: "The studio couldn't complete that request." }
            finally { busy = false }
        }
    }
    fun chooseImported(book: ImportedBook) = act {
        val file = imports.open(book.id) ?: error("That imported book is unavailable.")
        val text = withContext(Dispatchers.IO) { NativeEpubText.read(file).joinToString("\n\n") }
        if (allowed()) draft = PodcastDraft.extracted("book", book.title, text)
    }
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp).testTag("podcast_lounge"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            TextButton(onClick = onBack) { Text("Back to Listen") }
            Text("Podcast Lounge", style = MaterialTheme.typography.headlineLarge)
            AssistChip(onClick = { refresh++ }, label = { Text(if (wakingStudio) "Waking up the studio…" else status) })
            TextButton(onClick = { active.values.forEach { it.cancel() }; active.clear(); refresh++ }) { Text("Refresh jobs") }
            if (message == "Request access" || message == "Sign in to use Podcast Lounge") Button(onClick = onSettings) { Text(message!!) }
        }
        message?.let { item { Text(it, color = MaterialTheme.colorScheme.error) } }
        item {
            Card { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Make a podcast", style = MaterialTheme.typography.titleLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    linkedMapOf("book" to "From a book", "chapter" to "From a chapter", "article" to "Website or article", "topic" to "From a topic").forEach { (kind, label) ->
                        FilterChip(draft.kind == kind, { draft = PodcastDraft(kind) }, { Text(label) })
                    }
                }
                if (draft.kind == "book") {
                    Text("Pick a saved book or find a Gutenberg title.")
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        shelf.forEach { book -> AssistChip(onClick = { draft = PodcastDraft.book(book) }, label = { Text(book.title, maxLines = 2) }) }
                        imported.forEach { book -> AssistChip(onClick = { chooseImported(book) }, label = { Text("${book.title} · imported", maxLines = 2) }) }
                    }
                    OutlinedTextField(query, { query = it.take(120) }, label = { Text("Search Gutenberg") }, modifier = Modifier.fillMaxWidth())
                    OutlinedButton(enabled = query.isNotBlank() && !busy, onClick = { act { results = container.searchRepository.search(BookSource.GUTENBERG, query).value.books } }) { Text("Find books") }
                    results.forEach { book -> TextButton(onClick = { draft = PodcastDraft.book(book) }) { Text(book.title) } }
                    draft.gutenbergId?.let { Text("Gutenberg book $it selected") }
                }
                if (draft.kind == "chapter" && draft.text.isBlank()) Text("Open an imported book in the native Reader, then choose Make a podcast → This chapter. Or paste chapter text here.")
                OutlinedTextField(draft.title, { draft = draft.copy(title = it.take(200)) }, label = { Text("Podcast title") }, modifier = Modifier.fillMaxWidth())
                if (draft.kind == "article") OutlinedTextField(draft.url, { draft = draft.copy(url = it.take(4096)) }, label = { Text("HTTPS article or website URL") }, modifier = Modifier.fillMaxWidth())
                if (draft.gutenbergId == null) {
                    if (draft.kind in setOf("book", "chapter") && draft.text.length > 600 && !editingSource) {
                        Text(draft.text.take(600) + "…", maxLines = 6)
                        TextButton(onClick = { editingSource = true }) { Text("Edit source text") }
                    } else OutlinedTextField(draft.text, { draft = draft.copy(text = podcastText(it), originalChars = it.length) }, label = { Text(if (draft.kind == "topic") "What shall the hosts discuss?" else "Source text (optional for a URL)") }, minLines = 3, maxLines = 6, modifier = Modifier.fillMaxWidth())
                    Text("${draft.text.length} / 400,000 characters" + if (draft.originalChars > MAX_PODCAST_TEXT) " · trimmed from ${draft.originalChars}" else "", style = MaterialTheme.typography.bodySmall)
                }
                PodcastChoices(style, length, engine, { style = it }, { length = it }, { engine = it })
                Text("Two-host audio. Auto uses NotebookLM when available; the studio may fall back to local voices. Sources are sent through the public Mavis gateway to the studio.", style = MaterialTheme.typography.bodySmall)
                Button(enabled = !busy && accessReady && !sharedConfirm, onClick = { act { repo.create(draft.request(style, length, engine)); message = "Queued at your Mavis studio." } }) { Text(if (busy) "Working…" else "Make podcast") }
            } }
        }
        item { Text("Your podcasts", style = MaterialTheme.typography.titleLarge) }
        if (jobs.isEmpty()) item { Text("Your finished podcasts and studio jobs will appear here, including saved offline copies.") }
        items(jobs, key = { it.key }) { saved ->
            val job = saved.job
            var expanded by remember(saved.key) { mutableStateOf(false) }
            Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                PodcastCover(job.title)
                Text(job.title, style = MaterialTheme.typography.titleMedium)
                Text("${job.kind} · ${podcastStyles[job.style] ?: job.style} · ${job.engineLabel}")
                Text("${job.status} · ${job.stage}")
                if (!job.terminal) {
                    LinearProgressIndicator(progress = { (job.progress / 100).toFloat().coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                    pollingMessages[job.id]?.takeIf(String::isNotBlank)?.let { Text(it) }
                }
                job.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                job.durationSec?.let { Text("${it / 60} min ${it % 60} sec") }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (job.status == "ready") {
                        Button(enabled = !busy, onClick = { act {
                            val local = container.audioFiles.podcastLocal(saved.studio, job.id)
                            if (local == null && saved.studio != loaded.address) error("Choose this podcast's studio in Settings, or download it first.")
                            val uri = local?.let(Uri::fromFile) ?: Uri.parse(api.audioUrl(job.id))
                            val resume = container.resumePositionStore.load(saved.resumeKey)
                            if (allowed()) { onFirstPlay(); playback.playPodcast(saved, uri, resume) }
                        } }) { Text("Play") }
                        OutlinedButton(enabled = !busy && saved.studio == loaded.address, onClick = { act {
                            val file = container.audioFiles.downloadPodcast(api, job.id)
                            container.podcastSettings.save(saved.copy(localFile = file.absolutePath)); message = "Saved for offline."
                        } }) { Text(if (saved.localFile != null) "Downloaded" else "Download offline") }
                    }
                    if (job.status == "failed" && saved.request != null) OutlinedButton(enabled = !busy && saved.studio == loaded.address, onClick = { act { repo.create(saved.request) } }) { Text("Retry") }
                    TextButton(enabled = !busy, onClick = { delete = saved }) { Text(if (job.terminal) "Delete" else "Cancel / delete") }
                }
                if (!job.transcript.isNullOrBlank() || job.sources.isNotEmpty()) {
                    TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "Hide transcript and sources" else "Transcript and sources") }
                    if (expanded) {
                        job.transcript?.let { Text(it) }
                        job.sources.forEach { Text(it.title + (it.url?.let { url -> "\n$url" } ?: "")) }
                    }
                }
            } }
        }
        item { Spacer(Modifier.height(16.dp)) }
    }
    if (sharedConfirm) AlertDialog(onDismissRequest = { sharedConfirm = false; draft = PodcastDraft() }, title = { Text("Podcast this?") }, text = { Text(draft.url.ifBlank { "Shared text · ${draft.text.length} characters. Review the source and style before sending it to your home studio." }) }, confirmButton = { TextButton(onClick = { sharedConfirm = false }) { Text("Review podcast") } }, dismissButton = { TextButton(onClick = { sharedConfirm = false; draft = PodcastDraft() }) { Text("Not now") } })
    delete?.let { saved -> AlertDialog(onDismissRequest = { delete = null }, title = { Text("Delete podcast?") }, text = { Text("Delete ${saved.job.title} from this device and the configured studio? An active server job will be cancelled by the studio. Offline with no studio connection, deletion will not be claimed as complete.") }, confirmButton = { TextButton(onClick = { delete = null; act {
        require(saved.studio == loaded.address) { "Select this podcast's studio in Settings before deleting it." }
        active.remove(saved.job.id)?.cancelAndJoin()
        repo.delete(saved.job.id)
        container.audioFiles.deletePodcastCopy(saved.studio, saved.job.id)
    } }) { Text("Delete") } }, dismissButton = { TextButton(onClick = { delete = null }) { Text("Keep") } }) }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PodcastChoices(style: String, length: String, engine: String, onStyle: (String) -> Unit, onLength: (String) -> Unit, onEngine: (String) -> Unit) {
    Text("Style")
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { podcastStyles.forEach { (key, label) -> FilterChip(style == key, { onStyle(key) }, { Text(label) }) } }
    Text("Length")
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { podcastLengths.forEach { (key, label) -> FilterChip(length == key, { onLength(key) }, { Text(label) }) } }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(engine != "local", { onEngine("auto") }, { Text("Auto (NotebookLM when available)") })
        FilterChip(engine == "local", { onEngine("local") }, { Text("Local voices only") })
    }
}

@Composable
fun PodcastCover(title: String) {
    val hue = (title.hashCode().toLong() and 0x7fffffff).rem(360).toFloat()
    Box(Modifier.fillMaxWidth().height(100.dp).background(Brush.linearGradient(listOf(Color.hsv(hue, .55f, .45f), Color.hsv((hue + 70) % 360, .65f, .25f)))).padding(16.dp)) {
        Text(title, color = Color.White, style = MaterialTheme.typography.titleLarge, maxLines = 3)
    }
}
