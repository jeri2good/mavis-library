package com.mavislibrary.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
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
        if (widgetDestination != null && !loadedKids.enabled) {
            val tab = if (widgetDestination.startsWith("#/bible/")) Tab.BIBLE.route else Tab.SHELF.route
            goTab(tab)
        }
    }
    val scope = rememberCoroutineScope()
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
                MiniPlayer(playback, Modifier.background(MaterialTheme.colorScheme.surfaceContainerHigh))
                NavigationBar {
                    Tab.entries.forEach { tab ->
                        NavigationBarItem(
                            selected = current?.hierarchy?.any { it.route == tab.route } == true,
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
                                    Text(book.title + " · EPUB", Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                                }
                            }
                        }
                        Box(Modifier.weight(1f)) {
                    ShelfScreen(container, onOpenBook = openBook, onOpenWebShelf = {
                        webHost?.openLater("#/shelf")
                        nav.navigate(ROUTE_WEB_SHELF) { launchSingleTop = true }
                    }, onScan = if (scanRouteAllowed(loadedKids.enabled, canScan)) ({ nav.navigate(ROUTE_SCAN) }) else null)
                        }
                    }
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
                    SearchScreen(container, online, onOpenBook = openBook, onScan = if (scanRouteAllowed(loadedKids.enabled, canScan)) ({ nav.navigate(ROUTE_SCAN) }) else null)
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
                composable(Tab.LISTEN.route) { ListenScreen(container, playback, onFirstAudio) }
                composable(Tab.SETTINGS.route) { SettingsScreen(container, onOpenShowcase = { nav.navigate(ROUTE_SHOWCASE) }, onDriveModeChange = onDriveModeChange, onEnableReminder = onEnableReminder, onOpenStats = { nav.navigate("stats") }) }
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
            if (incoming.action == android.content.Intent.ACTION_SEND && incoming.type == "text/plain") {
                val verse = parseBibleReference(incoming.getCharSequenceExtra(android.content.Intent.EXTRA_TEXT)?.toString().orEmpty())
                if (verse == null) snackbar.showSnackbar(SHARE_HELP)
                else {
                    withContext(Dispatchers.Main.immediate) {
                        if (kidsPrefs.state.first().enabled) return@withContext
                        webHost?.openLater(verse.route)
                        // show() also consumes pending routes if the Bible tab is already attached.
                        if (nav.currentDestination?.route == Tab.BIBLE.route) webHost?.show(verse.route)
                        goTab(Tab.BIBLE.route)
                    }
                }
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
