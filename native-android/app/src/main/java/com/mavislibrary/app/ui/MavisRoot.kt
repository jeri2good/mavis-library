package com.mavislibrary.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.Headphones
import androidx.compose.material.icons.outlined.LocalLibrary
import androidx.compose.material.icons.outlined.RssFeed
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.collectAsState
import com.mavislibrary.app.data.kids.kidsPrefs
import com.mavislibrary.app.data.kids.KidsState
import com.mavislibrary.app.data.kids.adultRoutesAllowed
import com.mavislibrary.app.ui.kids.KidsRoot
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.mavislibrary.app.AppContainer
import com.mavislibrary.app.data.db.toEntity
import com.mavislibrary.app.data.kids.kidsBook
import com.mavislibrary.app.data.model.Book
import com.mavislibrary.app.work.WidgetUpdater
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import com.mavislibrary.app.ui.shelf.ShelfScreen
import com.mavislibrary.app.ui.search.SearchScreen
import com.mavislibrary.app.ui.scan.OpenLibraryScanResolver
import com.mavislibrary.app.ui.scan.PendingScans
import com.mavislibrary.app.ui.scan.ScanLookup
import com.mavislibrary.app.ui.scan.ScanScreen
import com.mavislibrary.app.ui.scan.scanRouteAllowed
import com.mavislibrary.app.ui.listen.ListenScreen
import com.mavislibrary.app.ui.listen.MiniPlayer
import com.mavislibrary.app.ui.listen.DriveModeScreen
import com.mavislibrary.app.audio.PlaybackController
import com.mavislibrary.app.ui.settings.SettingsScreen
import com.mavislibrary.app.ui.showcase.ThemeShowcaseScreen
import com.mavislibrary.app.web.WebHost
import com.mavislibrary.app.web.WebScreen

import androidx.compose.runtime.*
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import com.mavislibrary.app.data.share.*
import com.mavislibrary.app.data.stats.statsRepository
import com.mavislibrary.app.ui.stats.StatsScreen
import com.mavislibrary.app.ui.stats.AdultReadingTracker
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.first

enum class Tab(val route: String, val label: String, val icon: ImageVector, val webRoute: String? = null) {
    SHELF("shelf", "Shelf", Icons.Outlined.LocalLibrary),
    SEARCH("search", "Search", Icons.Outlined.Search),
    BIBLE("bible", "Bible", Icons.AutoMirrored.Outlined.MenuBook, "#/bible"),
    SITES("sites", "Sites", Icons.Outlined.RssFeed),
    LISTEN("listen", "Listen", Icons.Outlined.Headphones),
    SETTINGS("settings", "Settings", Icons.Outlined.Settings),
}

const val ROUTE_WEB_SHELF = "web_shelf"

const val ROUTE_SHOWCASE = "showcase"
const val ROUTE_SCAN = "scan"

/**
 * App shell. Back order: open dialogs and sheets consume Back themselves (they are separate windows),
 * then web history inside the current web tab, then the navigation back stack, then the app exits
 * from the start tab.
 */
@Composable
fun MavisRoot(
    container: AppContainer,
    webHost: WebHost?,
    online: Boolean,
    sharedIntent: android.content.Intent? = null,
    onShareConsumed: (android.content.Intent) -> Unit = {},
    onFirstAudio: () -> Unit = {},
    onEnableReminder: () -> Unit = {},
    onAppLockChange: (Boolean) -> Unit = {},
    driveModeActive: Boolean = false,
    onDriveModeChange: (Boolean) -> Unit = {},
    onCloseDriveMode: () -> Unit = {},
    widgetDestination: String? = null,
    widgetRequest: Int = 0,
) {
    val nav = rememberNavController()
    val context = LocalContext.current
    val playback = remember { PlaybackController(context) }
    DisposableEffect(playback) { onDispose { playback.close() } }
    val kidsPrefs = remember(context.applicationContext) { context.kidsPrefs() }
    val kids by kidsPrefs.state.collectAsState(initial = null)
    // Do not compose adult navigation until the persisted mode has been read.
    val loadedKids = kids ?: return
    LaunchedEffect(sharedIntent, loadedKids.enabled) {
        if (loadedKids.enabled && sharedIntent != null) onShareConsumed(sharedIntent)
    }
    if (!adultRoutesAllowed(loadedKids.enabled)) {
        KidsRoot(container, kidsPrefs, loadedKids, playback)
        return
    }
    val entry by nav.currentBackStackEntryAsState()
    val current = entry?.destination
    val voice = com.mavislibrary.app.voice.rememberVoiceUi(playback, loadedKids.enabled) { next ->
        val view = webHost?.webView
        val url = view?.url
        if (current?.route == ROUTE_WEB_SHELF && com.mavislibrary.app.web.isAppOrigin(url) &&
            android.net.Uri.parse(url).fragment.orEmpty().startsWith("/read/")) {
            webHost?.turnReaderPage(if (next) "next" else "prev")
            true
        } else false
    }
    val goTab: (String) -> Unit = { route ->
        nav.navigate(route) {
            popUpTo(nav.graph.findStartDestination().id) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    }
    val stats = remember { context.statsRepository() }
    val pendingScans = remember { PendingScans(context.applicationContext) }
    val scanResolver = remember(container) { OpenLibraryScanResolver(container.searchRepository) }
    val canScan = remember(context) { context.packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_CAMERA_ANY) }
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(online, loadedKids.enabled) {
        if (online && adultRoutesAllowed(loadedKids.enabled)) pendingScans.isbns.collect { scans ->
            scans.forEach { isbn ->
                when (val found = scanResolver.resolve(isbn)) {
                    is ScanLookup.Found -> {
                        container.shelfRepository.add(found.book)
                        pendingScans.remove(isbn)
                        snackbar.showSnackbar("Found ${found.book.title} from your saved ISBN and added it to your shelf")
                    }
                    ScanLookup.NotFound -> { pendingScans.remove(isbn); snackbar.showSnackbar("No book found for saved ISBN $isbn") }
                    ScanLookup.Offline, ScanLookup.Error -> Unit
                }
            }
        }
    }
    val imports = remember { EpubImports(context) }
    val importedBooks by imports.books.collectAsState(initial = emptyList())
    AdultReadingTracker(container, stats, webHost,
        current?.route == ROUTE_WEB_SHELF || current?.route == Tab.BIBLE.route)
    LaunchedEffect(widgetRequest, loadedKids.enabled) {
        if (widgetDestination != null && widgetDestination != com.mavislibrary.app.MainActivity.LOUNGE && !loadedKids.enabled) {
            val tab = when {
                widgetDestination.startsWith("#/bible/") -> Tab.BIBLE.route
                widgetDestination == com.mavislibrary.app.MainActivity.UPDATES -> Tab.SETTINGS.route
                else -> Tab.SHELF.route
            }
            goTab(tab)
        }
    }
    val scope = rememberCoroutineScope()
    var podcastDraft by remember { mutableStateOf<com.mavislibrary.app.data.podcast.PodcastDraft?>(null) }
    var podcastRequest by remember { mutableIntStateOf(0) }
    val openLounge: (com.mavislibrary.app.data.podcast.PodcastDraft) -> Unit = { draft ->
        if (com.mavislibrary.app.data.podcast.loungeRouteAllowed(loadedKids.enabled)) {
            podcastDraft = draft
            podcastRequest++
            nav.navigate("lounge") { launchSingleTop = true }
        }
    }
    var article by remember { mutableStateOf<com.mavislibrary.app.data.podcast.PodcastDraft?>(null) }
    val openArticle: (com.mavislibrary.app.data.podcast.PodcastDraft) -> Unit = { source ->
        if (com.mavislibrary.app.data.podcast.loungeRouteAllowed(loadedKids.enabled)) {
            article = source
            nav.navigate("article") { launchSingleTop = true }
        }
    }
    var readerTarget by remember { mutableStateOf<com.mavislibrary.app.ui.reader.ReaderTarget?>(null) }
    val openReader: (com.mavislibrary.app.ui.reader.ReaderTarget) -> Unit = { target ->
        if (com.mavislibrary.app.data.podcast.loungeRouteAllowed(loadedKids.enabled)) {
            readerTarget = target
            nav.navigate("reader") { launchSingleTop = true }
        }
    }
    // Same path the Lounge's subscription section uses: save each episode to the library, then queue gateway/local audio.
    val playEpisodes: (List<com.mavislibrary.app.data.podcast.PodcastJob>) -> Unit = { ready ->
        scope.launch {
            try {
                if (!com.mavislibrary.app.data.podcast.loungeRouteAllowed(kidsPrefs.state.first().enabled)) return@launch
                val studio = com.mavislibrary.app.BuildConfig.API_BASE
                val identity = container.podcastAccountId()
                val api = container.podcastApi()
                val queue = ready.map { job ->
                    val saved = com.mavislibrary.app.data.podcast.SavedPodcast(job, studio, accountId = identity)
                    container.podcastSettings.save(saved)
                    saved to (container.audioFiles.podcastLocal(studio, job.id)?.let(android.net.Uri::fromFile) ?: android.net.Uri.parse(api.audioUrl(job.id)))
                }
                if (queue.isNotEmpty()) { onFirstAudio(); playback.playPodcasts(queue, null) }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { snackbar.showSnackbar(e.message ?: "Couldn't play that episode. Try again.") }
        }
    }
    // "Podcast ready" notifications open the Lounge, with Listen behind it for Back.
    LaunchedEffect(widgetRequest, loadedKids.enabled) {
        if (widgetDestination == com.mavislibrary.app.MainActivity.LOUNGE && !loadedKids.enabled) {
            goTab(Tab.LISTEN.route)
            openLounge(com.mavislibrary.app.data.podcast.PodcastDraft())
        }
    }
    val latestLounge by rememberUpdatedState(openLounge)
    val latestArticle by rememberUpdatedState(openArticle)
    DisposableEffect(webHost) {
        webHost?.onPodcast = { latestLounge(it) }
        webHost?.onArticle = { latestArticle(it) }
        onDispose { webHost?.onPodcast = null; webHost?.onArticle = null }
    }
    val openBook: (Book) -> Unit = { book ->
        if (!kidsBook(book.source.name.lowercase(), book.sourceId)) scope.launch {
            container.database.books().upsert(book.toEntity(System.currentTimeMillis()))
            container.settings.setLastOpenedBook(book.key)
            WidgetUpdater.refresh(context)
        }
        webHost?.openLater("#/book/" + java.net.URLEncoder.encode(book.key, "UTF-8").replace("+", "%20"))
        nav.navigate(ROUTE_WEB_SHELF) {
            popUpTo(nav.graph.findStartDestination().id) { saveState = false }
            launchSingleTop = true
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            if (current?.route != ROUTE_SCAN) Column {
                MiniPlayer(playback, Modifier.background(MaterialTheme.colorScheme.surfaceContainerHigh), voice,
                    onOpen = { if (current?.route != Tab.LISTEN.route) goTab(Tab.LISTEN.route) })
                NavigationBar {
                    Tab.entries.forEach { tab ->
                        NavigationBarItem(
                            selected = current?.hierarchy?.any { it.route == tab.route } == true || tab == Tab.SITES && current?.route in setOf("site/{id}", "sites/new"),
                            onClick = {
                                nav.navigate(tab.route) {
                                    popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = { Icon(tab.icon, contentDescription = null) },
                            label = { Text(tab.label) },
                            modifier = Modifier.testTag("tab_${tab.route}"),
                        )
                    }
                }
            }
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .consumeWindowInsets(padding),
        ) {
            OfflineBanner(visible = !online)
            if (voice.message.isNotEmpty()) Text(voice.message, Modifier.fillMaxWidth().padding(12.dp)
                .semantics { liveRegion = androidx.compose.ui.semantics.LiveRegionMode.Polite })
            NavHost(nav, startDestination = Tab.SEARCH.route, modifier = Modifier.fillMaxSize()) {
                Tab.entries.filter { it.webRoute != null }.forEach { tab ->
                    composable(tab.route) {
                        if (webHost != null) {
                            BackHandler(enabled = webHost.canGoBack) { webHost.goBack() }
                            androidx.compose.runtime.key(if (tab == Tab.BIBLE) widgetRequest else 0) {
                                WebScreen(webHost, if (tab == Tab.BIBLE && widgetDestination?.startsWith("#/bible/") == true) widgetDestination else tab.webRoute!!, Modifier.fillMaxSize())
                            }
                        } else {
                            WebUnavailable(tab.label)
                        }
                    }
                }
                composable(Tab.SHELF.route) {
                    Column(Modifier.fillMaxSize()) {
                        if (importedBooks.isNotEmpty()) {
                            Text("Imported books · saved on this device", Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.titleMedium)
                            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 180.dp)) {
                                items(importedBooks, key = { it.id }) { book ->
                                    Text(book.title + " · EPUB", Modifier.fillMaxWidth().clickable {
                                        if (importedRouteAllowed(book.id, importedBooks.map { it.id }, loadedKids.enabled)) nav.navigate("imported/${book.id}")
                                    }.padding(horizontal = 16.dp, vertical = 8.dp))
                                }
                            }
                        }
                        Box(Modifier.weight(1f)) {
                    ShelfScreen(container, onOpenBook = openBook, onPodcast = { openLounge(com.mavislibrary.app.data.podcast.PodcastDraft.book(it)) }, onOpenWebShelf = {
                        webHost?.openLater("#/shelf")
                        nav.navigate(ROUTE_WEB_SHELF) { launchSingleTop = true }
                    }, onScan = if (scanRouteAllowed(loadedKids.enabled, canScan)) ({ nav.navigate(ROUTE_SCAN) }) else null)
                        }
                    }
                }
                composable("imported/{id}") { route ->
                    val id = route.arguments?.getString("id").orEmpty()
                    val book = importedBooks.find { it.id == id }
                    if (book != null && importedRouteAllowed(id, importedBooks.map { it.id }, loadedKids.enabled))
                        ImportedReader(id, book.title, imports, onBack = { nav.popBackStack() }, onPodcast = openLounge)
                    else LaunchedEffect(id) { nav.popBackStack() }
                }
                composable(ROUTE_WEB_SHELF) {
                    if (webHost != null) {
                        BackHandler(enabled = webHost.canGoBack) { webHost.goBack() }
                        WebScreen(webHost, "#/shelf", Modifier.fillMaxSize())
                    } else {
                        WebUnavailable("Shelf")
                    }
                }
                composable(Tab.SEARCH.route) {
                    SearchScreen(container, online, onOpenBook = openBook, onPodcast = openLounge, onOpenArticle = openArticle, onScan = if (scanRouteAllowed(loadedKids.enabled, canScan)) ({ nav.navigate(ROUTE_SCAN) }) else null,
                        onOpenSites = { goTab(Tab.SITES.route) })
                }
                composable(ROUTE_SCAN) {
                    if (scanRouteAllowed(loadedKids.enabled, canScan)) ScanScreen(
                        scanResolver, pendingScans, online,
                        onBack = { nav.popBackStack() },
                        onOpenBook = openBook,
                        onSaveBook = container.shelfRepository::add,
                        onSearch = { goTab(Tab.SEARCH.route) },
                    ) else LaunchedEffect(Unit) { nav.popBackStack() }
                }
                composable(Tab.SITES.route) {
                    com.mavislibrary.app.ui.sites.SitesScreen(
                        container,
                        onOpenSite = { id -> nav.navigate("site/$id") { launchSingleTop = true } },
                        onOpenLounge = { openLounge(com.mavislibrary.app.data.podcast.PodcastDraft()) },
                        onSettings = { goTab(Tab.SETTINGS.route) },
                        onAskNotifications = onFirstAudio,
                        onPlayEpisodes = playEpisodes,
                        onOpenAllNew = { nav.navigate("sites/new") { launchSingleTop = true } },
                        onOpenReader = openReader,
                    )
                }
                composable("site/{id}") { route ->
                    com.mavislibrary.app.ui.sites.SiteScreen(container, route.arguments?.getString("id").orEmpty(),
                        onBack = { nav.popBackStack() }, onPodcast = openLounge, onOpenReader = openReader)
                }
                composable("sites/new") {
                    com.mavislibrary.app.ui.sites.AllNewScreen(container, onBack = { nav.popBackStack() }, onOpenReader = openReader)
                }
                composable("reader") {
                    readerTarget?.takeIf { com.mavislibrary.app.data.podcast.loungeRouteAllowed(loadedKids.enabled) }?.let {
                        com.mavislibrary.app.ui.reader.ReaderScreen(container, it, onBack = { nav.popBackStack() }, onPodcast = openLounge, onOpenOriginal = openArticle)
                    } ?: LaunchedEffect(Unit) { nav.popBackStack() }
                }
                composable(Tab.LISTEN.route) { ListenScreen(container, playback, onFirstAudio, voice, onLounge = { openLounge(com.mavislibrary.app.data.podcast.PodcastDraft()) }) }
                composable("article") {
                    article?.takeIf { com.mavislibrary.app.data.podcast.loungeRouteAllowed(loadedKids.enabled) }?.let {
                        ArticleScreen(it, onBack = { nav.popBackStack() }, onPodcast = openLounge)
                    } ?: LaunchedEffect(Unit) { nav.popBackStack() }
                }
                composable("lounge") {
                    if (com.mavislibrary.app.data.podcast.loungeRouteAllowed(loadedKids.enabled)) key(podcastRequest) {
                        com.mavislibrary.app.ui.lounge.PodcastLoungeScreen(
                            container, playback, podcastDraft, onSettings = { goTab(Tab.SETTINGS.route) }, onBack = { nav.popBackStack() }, onFirstPlay = onFirstAudio, onOpenArticle = openArticle, onOpenReader = openReader,
                        )
                    } else LaunchedEffect(Unit) { nav.popBackStack() }
                }
                composable(Tab.SETTINGS.route) { SettingsScreen(container, voice = voice, onOpenShowcase = { nav.navigate(ROUTE_SHOWCASE) }, onDriveModeChange = onDriveModeChange, onEnableReminder = onEnableReminder, onOpenStats = { nav.navigate("stats") }, onAppLockChange = onAppLockChange) }
                composable("stats") { StatsScreen(stats, onBack = { nav.popBackStack() }) }
                composable(ROUTE_SHOWCASE) { ThemeShowcaseScreen(onBack = { nav.popBackStack() }) }
            }
        }
    }

    /** External deliveries can arrive before the start destination finishes entering. */
    LaunchedEffect(sharedIntent) {
        val incoming = sharedIntent ?: return@LaunchedEffect
        try {
            if (kidsPrefs.state.first().enabled) return@LaunchedEffect
            nav.currentBackStackEntryFlow.first().lifecycle.currentStateFlow.first { it.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED) }
            if (kidsPrefs.state.first().enabled) return@LaunchedEffect
            if (incoming.action == android.content.Intent.ACTION_SEND && incoming.type == "text/plain") {
                val shared = com.mavislibrary.app.data.podcast.routePodcastShare(
                    incoming.getCharSequenceExtra(android.content.Intent.EXTRA_TEXT)?.toString().orEmpty(),
                    incoming.getStringExtra(android.content.Intent.EXTRA_TITLE) ?: incoming.getStringExtra(android.content.Intent.EXTRA_SUBJECT).orEmpty(),
                )
                if (shared is com.mavislibrary.app.data.podcast.PodcastShare.Bible) {
                    withContext(Dispatchers.Main.immediate) {
                        if (kidsPrefs.state.first().enabled) return@withContext
                        webHost?.openLater(shared.route)
                        // show() also consumes pending routes if the Bible tab is already attached.
                        if (nav.currentDestination?.route == Tab.BIBLE.route) webHost?.show(shared.route)
                        goTab(Tab.BIBLE.route)
                    }
                } else if (shared is com.mavislibrary.app.data.podcast.PodcastShare.Lounge) withContext(Dispatchers.Main.immediate) {
                    if (!kidsPrefs.state.first().enabled) openLounge(shared.draft)
                } else snackbar.showSnackbar(SHARE_HELP)
            } else {
                val uri = sharedEpubUri(incoming)
                val mime = incoming.type ?: uri?.let { context.contentResolver.getType(it) }
                if (uri == null || mime != EPUB_MIME) snackbar.showSnackbar(SHARE_HELP)
                else {
                    val book = imports.import(uri, mime)
                    if (book != null) {
                        val navigated = withContext(Dispatchers.Main.immediate) {
                            if (kidsPrefs.state.first().enabled) false
                            else { goTab(Tab.SHELF.route); true }
                        }
                        if (navigated) snackbar.showSnackbar("Added ${book.title} to your shelf")
                    }
                }
            }
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { snackbar.showSnackbar((e as? ImportRejected)?.message ?: "Mavis couldn't open that share. Please try again.") }
        finally { onShareConsumed(incoming) }
    }

    webHost?.pendingMicRequest?.let {
        AlertDialog(
            onDismissRequest = webHost::onMicReasonDeclined,
            title = { Text("Use the microphone?") },
            text = { Text("Mavis listens only while you use a voice feature, and never records in the background.") },
            confirmButton = { TextButton(onClick = webHost::onMicReasonAccepted) { Text("Continue") } },
            dismissButton = { TextButton(onClick = webHost::onMicReasonDeclined) { Text("Not now") } },
        )
    }
    if (driveModeActive) DriveModeScreen(playback, onCloseDriveMode)
}

@Composable
fun OfflineBanner(visible: Boolean) {
    AnimatedVisibility(visible) {
        Row(
            Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.secondaryContainer)
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .semantics { contentDescription = "Offline" }
                .testTag("offline_banner"),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Outlined.CloudOff, contentDescription = null, tint = MaterialTheme.colorScheme.onSecondaryContainer)
            Spacer(Modifier.width(10.dp))
            Text(
                "Offline. Showing what's saved on this device.",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
        }
    }
}

@Composable
private fun WebUnavailable(label: String) {
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Text("$label opens here in the full app.", style = MaterialTheme.typography.bodyLarge)
    }
}
