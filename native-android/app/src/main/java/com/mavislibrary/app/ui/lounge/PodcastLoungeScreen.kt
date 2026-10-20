package com.mavislibrary.app.ui.lounge

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
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

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun PodcastLoungeScreen(container: AppContainer, playback: PlaybackController, initial: PodcastDraft?, onSettings: () -> Unit, onBack: () -> Unit, onFirstPlay: () -> Unit, onOpenArticle: ((PodcastDraft) -> Unit)? = null) {
    val context = LocalContext.current
    val kids by remember { context.kidsPrefs() }.state.collectAsStateWithLifecycle(initialValue = null)
    if (!loungeRouteAllowed(kids?.enabled)) return
    val prefs by container.podcastSettings.preferences.collectAsStateWithLifecycle(initialValue = null)
    val signedIn by container.accounts.signedIn.collectAsStateWithLifecycle(initialValue = null)
    val owner by container.secureStore.has(com.mavislibrary.app.data.secure.Secret.OWNER_ACCESS_CODE).collectAsStateWithLifecycle(initialValue = null)
    if (signedIn == null || owner == null) return
    if (signedIn != true && owner != true) {
        Column(Modifier.fillMaxSize().padding(16.dp).testTag("podcast_lounge"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            TextButton(onClick = onBack) { Text("Back") }
            Text("Podcast Lounge", style = MaterialTheme.typography.headlineLarge)
            Text("Sign in to use Podcast Lounge")
            Button(onClick = onSettings) { Text("Sign in in Settings") }
        }
        return
    }
    val loaded = (prefs ?: return).let { it.copy(address = com.mavislibrary.app.BuildConfig.API_BASE, style = it.style.takeIf { s -> s in podcastStyles } ?: "deep-dive") }
    val all by container.podcastSettings.library.collectAsStateWithLifecycle(initialValue = emptyList())
    val positionFlow = remember { container.resumePositionStore.podcastPositions() }
    val positions by positionFlow.collectAsStateWithLifecycle(initialValue = emptyMap())
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
    // Shortcuts (a chapter, an article, a book) arrive with something to make; plain visits open on the gallery.
    var composing by remember(initial) { mutableStateOf(initial != null && initial != PodcastDraft()) }
    var style by remember { mutableStateOf(loaded.style) }
    var length by remember { mutableStateOf(loaded.length) }
    var engine by remember { mutableStateOf(loaded.engine) }
    var accessReady by remember(signedIn, owner) { mutableStateOf(false) }
    var status by remember { mutableStateOf("Connecting to your studio…") }
    var message by remember { mutableStateOf<String?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<Book>>(emptyList()) }
    var editingSource by remember(initial) { mutableStateOf(false) }
    var moreOptions by remember { mutableStateOf(false) }
    var showPlayed by remember { mutableStateOf(false) }
    var refresh by remember { mutableIntStateOf(0) }
    val pollingMessages = remember(loaded.address) { mutableStateMapOf<String, String>() }
    val active = remember(loaded.address) { mutableMapOf<String, Job>() }
    var delete by remember { mutableStateOf<SavedPodcast?>(null) }
    var reading by remember { mutableStateOf<SavedPodcast?>(null) }
    var sharedConfirm by remember(initial) { mutableStateOf(initial?.confirmShare == true) }
    val jobs = all.filter { (identity == "owner" || identity != null && it.accountId == identity) && (it.studio == loaded.address || it.localFile != null) && !podcastExpired(it.job, loaded.cleanupDays) }.sortedByDescending { it.job.createdAt }
    val shelves = gallery(jobs, positions)

    suspend fun allowed() = loungeRouteAllowed(context.kidsPrefs().state.first().enabled)
    suspend fun refreshStudio() {
        try {
            if (!allowed()) return
            repo.refresh()
            accessReady = true; status = "Studio connected"; message = null
        } catch (e: CancellationException) { throw e } catch (e: Exception) {
            accessReady = false
            status = "Studio offline · downloaded podcasts still play"
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
                                repo.poll(saved.job.id).catch { pollingMessages[saved.job.id] = it.message ?: "Couldn't track this podcast. Tap Refresh to try again." }.collect { event ->
                                    pollingMessages[saved.job.id] = when (event) {
                                        is PodcastPoll.Updated -> ""
                                        PodcastPoll.Unreachable -> "Studio unreachable · trying again"
                                        PodcastPoll.TimedOut -> "Still not finished after 25 minutes. It may still finish; tap Refresh to check."
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
            busy = true; message = null; notice = null
            try { action() } catch (e: CancellationException) { throw e } catch (e: Exception) { message = e.message ?: "The studio couldn't complete that request." }
            finally { busy = false }
        }
    }
    fun chooseImported(book: ImportedBook) = act {
        val file = imports.open(book.id) ?: error("That imported book is unavailable.")
        val text = withContext(Dispatchers.IO) { NativeEpubText.read(file).joinToString("\n\n") }
        if (allowed()) draft = PodcastDraft.extracted("book", book.title, text)
    }
    fun uriOf(saved: SavedPodcast): Uri? = container.audioFiles.podcastLocal(saved.studio, saved.job.id)?.let(Uri::fromFile)
        ?: Uri.parse(api.audioUrl(saved.job.id)).takeIf { saved.studio == loaded.address }
    fun play(episode: Episode) = act {
        val queue = upNext(episode, shelves).mapNotNull { e -> uriOf(e.saved)?.let { e.saved to it } }
        if (queue.firstOrNull()?.first?.key != episode.saved.key) error("Choose this podcast's studio in Settings, or download it first.")
        val resume = container.resumePositionStore.load(episode.saved.resumeKey)?.takeIf { episode.shelf == PodcastShelf.CONTINUE }
        if (allowed()) { onFirstPlay(); playback.playPodcasts(queue, resume) }
    }

    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp).testTag("podcast_lounge"), contentPadding = PaddingValues(bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onBack) { Text("Back") }
                Text("Podcast Lounge", style = MaterialTheme.typography.headlineLarge)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(if (wakingStudio) "Waking up the studio…" else status, style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                    TextButton(onClick = { active.values.forEach { it.cancel() }; active.clear(); refresh++ }) { Text("Refresh") }
                }
                Button(onClick = { composing = true }, modifier = Modifier.fillMaxWidth().testTag("new_podcast")) {
                    Icon(Icons.Outlined.Add, contentDescription = null); Spacer(Modifier.width(8.dp)); Text("New podcast")
                }
                if (message == "Request access" || message == "Sign in to use Podcast Lounge") Button(onClick = onSettings) { Text(message!!) }
                message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                notice?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
            }
        }
        if (jobs.isEmpty()) item {
            Text("Your podcasts will appear here. Make one with New podcast, or turn on the daily podcast in the Sites tab.",
                Modifier.padding(vertical = 16.dp), style = MaterialTheme.typography.bodyLarge)
        }
        shelves.forEach { (section, episodes) ->
            item(key = "section-${section.name}") {
                Row(Modifier.fillMaxWidth().padding(top = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(section.label, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                    if (section == PodcastShelf.PLAYED) TextButton(onClick = { showPlayed = !showPlayed }) {
                        Text(if (showPlayed) "Hide" else "Show ${episodes.size}")
                    }
                }
            }
            if (section != PodcastShelf.PLAYED || showPlayed) items(episodes, key = { it.saved.key }) { episode ->
                EpisodeRow(
                    episode, busy, loaded.address, pollingMessages[episode.job.id],
                    onPlay = { play(episode) },
                    onDownload = { act {
                        val file = container.audioFiles.downloadPodcast(api, episode.job.id)
                        container.podcastSettings.save(episode.saved.copy(localFile = file.absolutePath)); notice = "Saved for offline listening."
                    } },
                    onRetry = episode.saved.request?.let { request -> { act { repo.create(request); notice = "Sent to your studio again." } } },
                    onRead = { reading = episode.saved },
                    onDelete = { delete = episode.saved },
                )
            }
        }
    }

    if (composing) ModalBottomSheet(onDismissRequest = { composing = false }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 24.dp).testTag("new_podcast_sheet"),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("New podcast", style = MaterialTheme.typography.headlineSmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                linkedMapOf("book" to "From a book", "chapter" to "From a chapter", "article" to "Website or article", "topic" to "From a topic").forEach { (kind, label) ->
                    FilterChip(draft.kind == kind, { draft = PodcastDraft(kind) }, { Text(label) })
                }
            }
            if (draft.kind == "book") {
                Text("Pick a saved book or find a free classic.")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    shelf.forEach { book -> AssistChip(onClick = { draft = PodcastDraft.book(book) }, label = { Text(book.title, maxLines = 2) }) }
                    imported.forEach { book -> AssistChip(onClick = { chooseImported(book) }, label = { Text("${book.title} · imported", maxLines = 2) }) }
                }
                OutlinedTextField(query, { query = it.take(120) }, label = { Text("Search free classics") }, modifier = Modifier.fillMaxWidth())
                OutlinedButton(enabled = query.isNotBlank() && !busy, onClick = { act { results = container.searchRepository.search(BookSource.GUTENBERG, query).value.books } }) { Text("Find books") }
                results.forEach { book -> TextButton(onClick = { draft = PodcastDraft.book(book) }) { Text(book.title) } }
                draft.gutenbergId?.let { Text("Chosen: ${draft.title}", style = MaterialTheme.typography.bodySmall) }
            }
            if (draft.kind == "chapter" && draft.text.isBlank()) Text("Open a book and choose Make podcast of this chapter, or paste the chapter text here.")
            OutlinedTextField(draft.title, { draft = draft.copy(title = it.take(200)) }, label = { Text("Podcast title") }, modifier = Modifier.fillMaxWidth())
            if (draft.kind == "article") {
                OutlinedTextField(draft.url, { draft = draft.copy(url = it.take(4096)) }, label = { Text("Web address (https://…)") }, modifier = Modifier.fillMaxWidth())
                if (onOpenArticle != null && draft.url.isNotBlank()) TextButton(onClick = { composing = false; onOpenArticle(draft) }) { Text("Read article") }
            }
            if (draft.gutenbergId == null) {
                if (draft.kind in setOf("book", "chapter") && draft.text.length > 600 && !editingSource) {
                    Text(draft.text.take(600) + "…", maxLines = 6, style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = { editingSource = true }) { Text("Edit source text") }
                } else OutlinedTextField(draft.text, { draft = draft.copy(text = podcastText(it), originalChars = it.length) },
                    label = { Text(if (draft.kind == "topic") "What should the hosts talk about?" else "Source text (optional with a web address)") },
                    minLines = 3, maxLines = 6, modifier = Modifier.fillMaxWidth())
                if (draft.kind != "topic" && draft.text.isNotEmpty()) Text("${draft.text.length} / 400,000 characters" +
                    if (draft.originalChars > MAX_PODCAST_TEXT) " · trimmed from ${draft.originalChars}" else "", style = MaterialTheme.typography.bodySmall)
            }
            Text("${podcastStyles[style] ?: style} · ${podcastLengths[length] ?: length} · ${if (engine == "local") "local voices" else "NotebookLM when available"}",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton(onClick = { moreOptions = !moreOptions }, contentPadding = PaddingValues(0.dp)) { Text(if (moreOptions) "Fewer options" else "More options") }
            if (moreOptions) {
                PodcastChoices(style, length, engine, { style = it }, { length = it }, { engine = it })
                Text("Two hosts talk it through. Auto uses NotebookLM when available; the studio may fall back to local voices. Sources go through the Mavis gateway to your studio.",
                    style = MaterialTheme.typography.bodySmall)
            }
            message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Button(enabled = !busy && accessReady && !sharedConfirm, modifier = Modifier.fillMaxWidth(), onClick = { act {
                repo.create(draft.request(style, length, engine))
                notice = "Sent to your studio. It appears under Being made."
                composing = false
            } }) { Text(if (busy) "Working…" else "Make podcast") }
            if (!accessReady) Text("Waiting for your studio to connect.", style = MaterialTheme.typography.bodySmall)
        }
    }
    reading?.let { saved ->
        AlertDialog(onDismissRequest = { reading = null }, title = { Text(saved.job.title) }, text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                saved.job.transcript?.takeIf(String::isNotBlank)?.let { Text(it) } ?: Text("No transcript yet.")
                if (saved.job.sources.isNotEmpty()) {
                    Text("Sources", style = MaterialTheme.typography.titleSmall)
                    saved.job.sources.forEach { Text(it.title + (it.url?.let { url -> "\n$url" } ?: ""), style = MaterialTheme.typography.bodySmall) }
                }
            }
        }, confirmButton = { TextButton(onClick = { reading = null }) { Text("Close") } })
    }
    if (sharedConfirm) AlertDialog(onDismissRequest = { sharedConfirm = false; composing = false; draft = PodcastDraft() }, title = { Text("Podcast this?") }, text = { Text(draft.url.ifBlank { "Shared text · ${draft.text.length} characters. Review the source and style before sending it to your home studio." }) }, confirmButton = { TextButton(onClick = { sharedConfirm = false }) { Text("Review podcast") } }, dismissButton = { TextButton(onClick = { sharedConfirm = false; composing = false; draft = PodcastDraft() }) { Text("Not now") } })
    delete?.let { saved -> AlertDialog(onDismissRequest = { delete = null }, title = { Text("Delete podcast?") }, text = { Text("Delete ${saved.job.title} from this phone and from your studio? A podcast still being made is cancelled. Without a studio connection, deletion can't finish.") }, confirmButton = { TextButton(onClick = { delete = null; act {
        require(saved.studio == loaded.address) { "Select this podcast's studio in Settings before deleting it." }
        active.remove(saved.job.id)?.cancelAndJoin()
        repo.delete(saved.job.id)
        container.audioFiles.deletePodcastCopy(saved.studio, saved.job.id)
    } }) { Text("Delete") } }, dismissButton = { TextButton(onClick = { delete = null }) { Text("Keep") } }) }
}

@Composable
internal fun EpisodeRow(
    episode: Episode, busy: Boolean, studio: String, polling: String?,
    onPlay: () -> Unit, onDownload: () -> Unit, onRetry: (() -> Unit)?, onRead: () -> Unit, onDelete: () -> Unit,
) {
    val job = episode.job
    val ready = job.status == "ready"
    var menu by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium).clickable(enabled = ready && !busy, onClick = onPlay).padding(vertical = 8.dp).testTag("episode_${job.id}"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PodcastThumb(job.title)
        Column(Modifier.weight(1f).padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(job.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            when (episode.shelf) {
                PodcastShelf.MAKING -> {
                    Text(stageLabel(job), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                    LinearProgressIndicator(progress = { (job.progress / 100).toFloat().coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                    polling?.takeIf(String::isNotBlank)?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                }
                PodcastShelf.FAILED -> Text(job.error ?: "The studio couldn't make this one.", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error, maxLines = 3, overflow = TextOverflow.Ellipsis)
                else -> {
                    Text(episodeDetails(episode), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (episode.shelf == PodcastShelf.CONTINUE) episode.heard?.let { LinearProgressIndicator(progress = { it }, modifier = Modifier.fillMaxWidth()) }
                }
            }
        }
        if (ready) FilledTonalIconButton(onClick = onPlay, enabled = !busy) { Icon(Icons.Outlined.PlayArrow, contentDescription = "Play ${job.title}") }
        Box {
            IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreVert, contentDescription = "Options for ${job.title}") }
            DropdownMenu(menu, { menu = false }) {
                if (ready && episode.saved.localFile == null && episode.saved.studio == studio)
                    DropdownMenuItem({ Text("Download for offline") }, { menu = false; onDownload() })
                if (!job.transcript.isNullOrBlank() || job.sources.isNotEmpty())
                    DropdownMenuItem({ Text("Transcript and sources") }, { menu = false; onRead() })
                if (job.status == "failed" && onRetry != null && episode.saved.studio == studio)
                    DropdownMenuItem({ Text("Try again") }, { menu = false; onRetry() })
                DropdownMenuItem({ Text(if (job.terminal) "Delete" else "Cancel and delete") }, { menu = false; onDelete() })
            }
        }
    }
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

private fun coverColors(title: String): List<Color> {
    val hue = (title.hashCode().toLong() and 0x7fffffff).rem(360).toFloat()
    return listOf(Color.hsv(hue, .55f, .45f), Color.hsv((hue + 70) % 360, .65f, .25f))
}

@Composable
fun PodcastCover(title: String) {
    Box(Modifier.fillMaxWidth().height(100.dp).background(Brush.linearGradient(coverColors(title))).padding(16.dp)) {
        Text(title, color = Color.White, style = MaterialTheme.typography.titleLarge, maxLines = 3)
    }
}

/** Small square version of [PodcastCover] for gallery rows. */
@Composable
internal fun PodcastThumb(title: String) {
    val initials = title.split(' ', '·', ':', '-').filter { it.firstOrNull()?.isLetterOrDigit() == true }.take(2).joinToString("") { it.take(1).uppercase() }
    Box(Modifier.size(56.dp).clip(RoundedCornerShape(10.dp)).background(Brush.linearGradient(coverColors(title))), contentAlignment = Alignment.Center) {
        Text(initials, color = Color.White, style = MaterialTheme.typography.titleMedium)
    }
}
