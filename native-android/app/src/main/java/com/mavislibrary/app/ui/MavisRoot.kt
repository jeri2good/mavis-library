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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
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
import com.mavislibrary.app.ui.search.SearchScreen
import com.mavislibrary.app.ui.settings.SettingsScreen
import com.mavislibrary.app.ui.showcase.ThemeShowcaseScreen
import com.mavislibrary.app.web.WebHost
import com.mavislibrary.app.web.WebScreen

enum class Tab(val route: String, val label: String, val icon: ImageVector, val webRoute: String? = null) {
    SHELF("shelf", "Shelf", Icons.Outlined.LocalLibrary, "#/shelf"),
    SEARCH("search", "Search", Icons.Outlined.Search),
    BIBLE("bible", "Bible", Icons.AutoMirrored.Outlined.MenuBook, "#/bible"),
    LISTEN("listen", "Listen", Icons.Outlined.Headphones, "#/listen"),
    SETTINGS("settings", "Settings", Icons.Outlined.Settings),
}

const val ROUTE_SHOWCASE = "showcase"

/**
 * App shell. Back order: open dialogs and sheets consume Back themselves (they are separate windows),
 * then web history inside the current web tab, then the navigation back stack, then the app exits
 * from the start tab.
 */
@Composable
fun MavisRoot(container: AppContainer, webHost: WebHost?, online: Boolean) {
    val nav = rememberNavController()
    val entry by nav.currentBackStackEntryAsState()
    val current = entry?.destination

    Scaffold(
        bottomBar = {
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
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .consumeWindowInsets(padding),
        ) {
            OfflineBanner(visible = !online)
            NavHost(nav, startDestination = Tab.SHELF.route, modifier = Modifier.fillMaxSize()) {
                Tab.entries.filter { it.webRoute != null }.forEach { tab ->
                    composable(tab.route) {
                        if (webHost != null) {
                            BackHandler(enabled = webHost.canGoBack) { webHost.goBack() }
                            WebScreen(webHost, tab.webRoute!!, Modifier.fillMaxSize())
                        } else {
                            WebUnavailable(tab.label)
                        }
                    }
                }
                composable(Tab.SEARCH.route) { SearchScreen(container, online) }
                composable(Tab.SETTINGS.route) { SettingsScreen(container, onOpenShowcase = { nav.navigate(ROUTE_SHOWCASE) }) }
                composable(ROUTE_SHOWCASE) { ThemeShowcaseScreen(onBack = { nav.popBackStack() }) }
            }
        }
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
