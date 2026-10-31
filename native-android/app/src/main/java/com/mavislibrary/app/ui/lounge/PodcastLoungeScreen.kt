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
import androidx.lifecycle.viewmodel.initializer
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
import com.mavislibrary.app.ui.podcast.EpisodeDetailsSheet
import com.mavislibrary.app.ui.podcast.episodeHeadline
import com.mavislibrary.app.ui.reader.ReaderTarget
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.catch

private data class DetailsTarget(val saved: SavedPodcast, val onPlay: () -> Unit)
private data class ExportProgressState(val label: String, val fraction: Float?)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun PodcastLoungeScreen(container: AppContainer, playback: PlaybackController, initial: PodcastDraft?, onSettings: () -> Unit, onBack: () -> Unit, onFirstPlay: () -> Unit, onOpenArticle: ((PodcastDraft) -> Unit)? = null, onOpenReader: ((ReaderTarget) -> Unit)? = null, pickerWebView: android.webkit.WebView? = null) {
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
    // Books on the shelf first, then any other book this device has opened (most recently read first).
    val seenBooks by androidx.compose.runtime.produceState(emptyList<com.mavislibrary.app.data.model.Book>(), shelf) {
        val recent = runCatching { container.database.books().recent() }.getOrDefault(emptyList())
        value = shelf + recent.map { with(com.mavislibrary.app.data.db.EntityMappers) { it.asBook() } }.filter { b -> shelf.none { it.key == b.key } }
    }
    val imports = remember { EpubImports(context) }
    val imported by imports.books.collectAsStateWithLifecycle(initialValue = emptyList())
    val scope = rememberCoroutineScope()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val accountId by container.secureStore.observe(com.mavislibrary.app.data.secure.Secret.ACCOUNT_USER_ID).collectAsStateWithLifecycle(initialValue = null)
    val identity = if (owner == true) "owner" else accountId
    val subscription: PodsubViewModel = androidx.lifecycle.viewmodel.compose.viewModel(key = "podsub-$identity",
        factory = androidx.lifecycle.viewmodel.viewModelFactory { initializer { PodsubViewModel(container.podsubRepository) } })
    val subscriptionState by subscription.state.collectAsStateWithLifecycle()
    LaunchedEffect(identity, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) { while (isActive) { subscription.refresh(); delay(60_000) } }
    }
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
    var editingSource by remember(initial) { mutableStateOf(false) }
    var picker by remember { mutableStateOf<String?>(null) }
    var chosenKind by remember(initial) { mutableStateOf(initial?.let { if (it.title.startsWith("Bible - ")) "bible" else it.kind }) }
    var confirmed by remember(initial) { mutableStateOf(initial != null && initial != PodcastDraft()) }
    var moreOptions by remember { mutableStateOf(false) }
    var refresh by remember { mutableIntStateOf(0) }
    val pollingMessages = remember(loaded.address) { mutableStateMapOf<String, String>() }
    val active = remember(loaded.address) { mutableMapOf<String, Job>() }
    var delete by remember { mutableStateOf<SavedPodcast?>(null) }
    var reading by remember { mutableStateOf<SavedPodcast?>(null) }
    var details by remember { mutableStateOf<DetailsTarget?>(null) }
    var exportProgress by remember { mutableStateOf<ExportProgressState?>(null) }
    val snackbar = remember { SnackbarHostState() }
    var sharedConfirm by remember(initial) { mutableStateOf(initial?.confirmShare == true) }
    val jobs = visiblePodcasts(all, identity, loaded.address, loaded.cleanupDays)
    val nowPlaying by playback.state.collectAsStateWithLifecycle()
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
        if (allowed()) { draft = PodcastDraft.extracted("book", book.title, text); confirmed = true; picker = null; editingSource = false }
    }
    fun uriOf(saved: SavedPodcast): Uri? = container.audioFiles.podcastLocal(saved.studio, saved.job.id)?.let(Uri::fromFile)
        ?: Uri.parse(api.audioUrl(saved.job.id)).takeIf { saved.studio == loaded.address }
    fun play(episode: Episode, next: List<Episode> = upNext(episode, shelves)) = act {
        val queue = playableQueue(next, ::uriOf)
        if (queue.firstOrNull()?.first?.key != episode.saved.key) error("Choose this podcast's studio in Settings, or download it first.")
        val resume = container.resumePositionStore.load(episode.saved.resumeKey)?.takeIf { episode.shelf == PodcastShelf.CONTINUE }
        if (allowed()) { onFirstPlay(); playback.playPodcasts(queue, resume) }
    }
    fun playToday(ready: List<PodcastJob>) = act {
        val queue = ready.map { job ->
            val saved = SavedPodcast(job, loaded.address, accountId = identity)
            container.podcastSettings.save(saved)
            saved to (uriOf(saved) ?: error("Audio unavailable"))
        }
        if (queue.isNotEmpty() && allowed()) { onFirstPlay(); playback.playPodcasts(queue, null) }
    }
    fun savedFor(job: PodcastJob): SavedPodcast =
        jobs.firstOrNull { it.job.id == job.id }?.copy(job = job)
            ?: SavedPodcast(job, loaded.address, localFile = container.audioFiles.podcastLocal(loaded.address, job.id)?.absolutePath, accountId = identity)
    fun saveMp3(saved: SavedPodcast) {
        scope.launch {
            if (busy || !allowed() || saved.job.status != "ready") return@launch
            busy = true; message = null; notice = null
            exportProgress = ExportProgressState("Saving MP3…", null)
            var exported: SavedPodcastMp3? = null
            try {
                val source = api.asAudioSource().takeIf { saved.studio == loaded.address }
                exported = savePodcastMp3(
                    context = context,
                    saved = saved,
                    audioSource = source,
                    localLookup = { container.audioFiles.podcastLocal(it.studio, it.job.id) },
                    onProgress = { written, total ->
                        exportProgress = ExportProgressState(
                            label = formatExportProgressLabel("Saving MP3…", written, total),
                            fraction = if (total > 0L) (written.toFloat() / total.toFloat()).coerceIn(0f, 1f) else null,
                        )
                    },
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                message = e.message ?: "Couldn't save MP3."
            } finally {
                exportProgress = null
                busy = false
            }
            if (exported != null) {
                val action = snackbar.showSnackbar(
                    message = SAVED_MP3_SNACKBAR,
                    actionLabel = "Open",
                    duration = SnackbarDuration.Long,
                )
                if (action == SnackbarResult.ActionPerformed) {
                    openSavedPodcastMp3(context, exported.uri)
                }
            }
        }
    }
    fun shareMp3(saved: SavedPodcast) {
        scope.launch {
            if (busy || !allowed() || saved.job.status != "ready") return@launch
            busy = true; message = null; notice = null
            exportProgress = ExportProgressState("Preparing MP3…", null)
            try {
                val source = api.asAudioSource().takeIf { saved.studio == loaded.address }
                sharePodcastMp3(
                    context = context,
                    saved = saved,
                    audioSource = source,
                    localLookup = { container.audioFiles.podcastLocal(it.studio, it.job.id) },
                    onProgress = { written, total ->
                        exportProgress = ExportProgressState(
                            label = formatExportProgressLabel("Preparing MP3…", written, total),
                            fraction = if (total > 0L) (written.toFloat() / total.toFloat()).coerceIn(0f, 1f) else null,
                        )
                    },
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                message = e.message ?: "Couldn't share MP3."
            } finally {
                exportProgress = null
                busy = false
            }
        }
    }

    Box(Modifier.fillMaxSize()) {
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
                    Button(onClick = { composing = true; confirmed = false; chosenKind = null; editingSource = false }, modifier = Modifier.fillMaxWidth().testTag("new_podcast")) {
                        Icon(Icons.Outlined.Add, contentDescription = null); Spacer(Modifier.width(8.dp)); Text("New podcast")
                    }
                    if (message == "Request access" || message == "Sign in to use Podcast Lounge") Button(onClick = onSettings) { Text(message!!) }
                    message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    notice?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
                    exportProgress?.let { progress ->
                        Column(Modifier.fillMaxWidth().testTag("podcast_export_progress"), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(progress.label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
                            if (progress.fraction != null) LinearProgressIndicator(progress = { progress.fraction }, modifier = Modifier.fillMaxWidth())
                            else LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        }
                    }
                }
            }
            item {
                PodsubSection(subscriptionState, subscription, onDetails = { job -> details = DetailsTarget(savedFor(job)) { playToday(listOf(job)) } }, onPlay = ::playToday)
            }
            if (jobs.isEmpty()) item {
                Text("Your podcasts will appear here. Make one with New podcast, or set a subscription schedule above.",
                    Modifier.padding(vertical = 16.dp), style = MaterialTheme.typography.bodyLarge)
            }
            if (jobs.isNotEmpty()) item(key = "records") {
                RecordSelector(
                    shelves, nowPlaying.mediaId, nowPlaying.playing, enabled = !busy,
                    onPlay = ::play,
                    onToggle = playback::toggle,
                    onDetails = { episode -> details = DetailsTarget(episode.saved) { play(episode) } },
                    modifier = Modifier.padding(top = 16.dp),
                    polling = pollingMessages,
                    menu = { episode, close ->
                        val job = episode.job
                        if (job.status == "ready") {
                            DropdownMenuItem({ Text("Save MP3") }, { close(); saveMp3(episode.saved) })
                            DropdownMenuItem({ Text("Share") }, { close(); shareMp3(episode.saved) })
                        }
                        if (job.status == "ready" && episode.saved.localFile == null && episode.saved.studio == loaded.address)
                            DropdownMenuItem({ Text("Download for offline") }, { close(); act {
                                val file = container.audioFiles.downloadPodcast(api, job.id)
                                container.podcastSettings.save(episode.saved.copy(localFile = file.absolutePath)); notice = "Saved for offline listening."
                            } })
                        if (!job.transcript.isNullOrBlank() || job.sources.isNotEmpty())
                            DropdownMenuItem({ Text("Transcript and sources") }, { close(); reading = episode.saved })
                        val request = episode.saved.request
                        if (job.status == "failed" && request != null && episode.saved.studio == loaded.address)
                            DropdownMenuItem({ Text("Try again") }, { close(); act { repo.create(request); notice = "Sent to your studio again." } })
                        DropdownMenuItem({ Text(if (job.terminal) "Delete" else "Cancel and delete") }, { close(); delete = episode.saved })
                    },
                )
            }
        }
        SnackbarHost(
            hostState = snackbar,
            modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp).testTag("podcast_lounge_snackbar"),
        )
    }

    if (composing) ModalBottomSheet(onDismissRequest = { composing = false }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 24.dp).testTag("new_podcast_sheet"),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("New podcast", style = MaterialTheme.typography.headlineSmall)
            if (!confirmed) PodcastTypeCards { picker = it; chosenKind = it }
            else {
                OutlinedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(sourceTypes[chosenKind] ?: draft.kind, style = MaterialTheme.typography.labelLarge)
                        Text(draft.title.ifBlank { draft.text.take(100) }, style = MaterialTheme.typography.titleLarge)
                        Text(when {
                            chosenKind == "bible" -> "Bible lesson · ${draft.title.removePrefix("Bible - ")} · ${draft.text.lines().count { it.matches(Regex("^\\d+ .*")) }} verses"
                            draft.kind == "chapter" -> "${podcastStyles[style] ?: style} · ${draft.title} · ${java.text.NumberFormat.getIntegerInstance().format(draft.originalChars)} characters"
                            draft.kind == "article" -> draft.url
                            draft.gutenbergId != null -> "Free classic · Gutenberg ${draft.gutenbergId}"
                            else -> "${draft.originalChars} characters"
                        })
                        TextButton(onClick = { picker = chosenKind ?: draft.kind }) { Text("Change") }
                    }
                }
                TextButton(onClick = { editingSource = !editingSource }) { Text(if (editingSource) "Hide details" else "Edit details") }
                if (editingSource) {
                    OutlinedTextField(draft.title, { draft = draft.copy(title = it.take(200)) }, label = { Text("Podcast title") }, modifier = Modifier.fillMaxWidth())
                    if (draft.kind == "article") OutlinedTextField(draft.url, { draft = draft.copy(url = it.take(4096)) }, label = { Text("Web address") }, modifier = Modifier.fillMaxWidth())
                    if (draft.gutenbergId == null) OutlinedTextField(draft.text, { draft = draft.copy(text = podcastText(it), originalChars = it.length) }, label = { Text(if (draft.kind == "topic") "What should the hosts talk about?" else "Source text") }, minLines = 3, maxLines = 6, modifier = Modifier.fillMaxWidth())
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
    }
    picker?.let { kind ->
        PodcastSourcePicker(kind, container, seenBooks, imported, pickerWebView, onDismiss = { picker = null }, onPick = {
            draft = it; confirmed = true; editingSource = false; picker = null
        }, onImported = ::chooseImported)
    }
    details?.let { target ->
        EpisodeDetailsSheet(
            job = target.saved.job,
            onDismiss = { details = null },
            onPlay = target.onPlay,
            onOpenReader = onOpenReader,
            onSaveMp3 = { saveMp3(target.saved) },
            onShare = { shareMp3(target.saved) },
            actionsEnabled = !busy,
        )
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
