package com.mavislibrary.app.ui.search

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.mavislibrary.app.data.feeds.DiscoveryResult
import com.mavislibrary.app.data.feeds.FeedSource
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Only composed inside Discover's existing adult navigation gate. No external route/deep link. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun MyTechSites(state: SearchUiState, vm: SearchViewModel) {
    var managing by remember { mutableStateOf<FeedSource?>(null) }
    var rename by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf<String?>(null) }
    Column {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AssistChip(onClick = vm::openAddSite, label = { Text("+ Add site") }, modifier = Modifier.testTag("add_tech_site"))
            if (state.sources.isNotEmpty()) {
                FilterChip(selected = state.techKind == "mine", onClick = { vm.loadTech("mine") }, label = { Text("Mine") })
                TextButton(onClick = { confirm = "manage" }) { Text("Manage sites") }
            }
            state.sources.filter { it.enabled }.forEach { source ->
                FilterChip(selected = state.techKind == "site:${source.id}", onClick = { vm.loadTech("site:${source.id}") }, label = { Text(source.name) })
            }
        }
        state.sourceError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }
    if (state.addSiteOpen) AlertDialog(
        onDismissRequest = vm::closeAddSite,
        title = { Text("My tech sites") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Fetched by your phone directly from the site")
                OutlinedTextField(value = state.siteInput, onValueChange = vm::siteInput, singleLine = true,
                    label = { Text("Site or feed address") }, placeholder = { Text("intheworldofai.com or a feed link") }, modifier = Modifier.testTag("tech_site_address"))
                SuggestionChip(onClick = { vm.siteInput("https://intheworldofai.com/"); vm.findFeed() }, label = { Text("Try World of AI") })
                Button(onClick = vm::findFeed, enabled = !state.findingFeed && state.siteInput.isNotBlank()) { Text("Find feed") }
                if (state.findingFeed) { CircularProgressIndicator(); Text("Looking for a feed…") }
                when (val result = state.feedResult) {
                    is DiscoveryResult.Found -> Card {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("Found: ${result.title}")
                            Text("${result.items} posts" + (result.newest?.let {
                                " · newest " + DateTimeFormatter.ofPattern("MMM d, yyyy").withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(it))
                            } ?: " · date unavailable"))
                            Text(result.feedUrl, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    is DiscoveryResult.NotFound -> Text(result.message)
                    null -> Unit
                }
                state.sourceError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = { if (state.feedResult is DiscoveryResult.Found) TextButton(onClick = vm::addFeed) { Text("Add") } },
        dismissButton = { TextButton(onClick = vm::closeAddSite) { Text("Cancel") } },
    )
    if (confirm == "manage") AlertDialog(
        onDismissRequest = { confirm = null }, title = { Text("My tech sites") },
        text = { Column(Modifier.verticalScroll(rememberScrollState())) {
            state.sources.forEach { source -> TextButton(onClick = { managing = source; rename = source.name; confirm = null }) {
                Text(source.name + if (source.enabled) " · Edit" else " · Disabled · Edit")
            } }
        } }, confirmButton = { TextButton(onClick = { confirm = null }) { Text("Done") } },
    )
    managing?.let { source ->
        AlertDialog(onDismissRequest = { managing = null; confirm = null }, title = { Text("Edit site") }, text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = rename, onValueChange = { rename = it.take(80) }, label = { Text("Name") })
                TextButton(onClick = { confirm = if (source.enabled) "disable" else "enable" }) { Text(if (source.enabled) "Disable" else "Enable") }
                TextButton(onClick = { confirm = "remove" }) { Text("Remove") }
                if (confirm in listOf("disable", "enable", "remove")) {
                    Text("${confirm!!.replaceFirstChar { it.uppercase() }} ${source.name}?")
                    TextButton(onClick = {
                        if (confirm == "remove") vm.removeFeed(source.id) else vm.enableFeed(source.id, confirm == "enable")
                        managing = null; confirm = null
                    }) { Text("Confirm") }
                }
            }
        }, confirmButton = { TextButton(enabled = rename.isNotBlank(), onClick = { vm.renameFeed(source.id, rename); managing = null }) { Text("Save name") } },
            dismissButton = { TextButton(onClick = { managing = null; confirm = null }) { Text("Cancel") } })
    }
}
