package com.mavislibrary.app.ui.sites

import android.app.TimePickerDialog
import android.text.format.DateUtils
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.mavislibrary.app.AppContainer
import com.mavislibrary.app.data.briefing.Briefing
import com.mavislibrary.app.data.feeds.DiscoveryResult
import com.mavislibrary.app.data.feeds.FeedLoad
import com.mavislibrary.app.data.feeds.FeedSource
import com.mavislibrary.app.data.feeds.SitePodcast
import com.mavislibrary.app.data.podcast.PodcastDraft
import com.mavislibrary.app.data.secure.Secret
import com.mavislibrary.app.work.BriefingScheduler
import kotlinx.coroutines.launch
import java.net.URI
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

private val SHORT_TIME = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)
private val POST_DAY = DateTimeFormatter.ofPattern("MMM d")

/** The sites the reader follows, their latest posts, and the daily podcast made from them. */
@Composable
fun SitesScreen(
    container: AppContainer,
    onOpenSite: (String) -> Unit,
    onOpenLounge: () -> Unit,
    onSettings: () -> Unit,
    onAskNotifications: () -> Unit,
) {
    val context = LocalContext.current
    val appContext = context.applicationContext
    val vm: SitesViewModel = viewModel(factory = viewModelFactory {
        initializer {
            SitesViewModel(container.feedStore, container.feedDiscovery, container.feedRepository, container.briefingStore) {
                BriefingScheduler.sync(appContext)
            }
        }
    })
    val state by vm.state.collectAsStateWithLifecycle()
    val signedIn by container.accounts.signedIn.collectAsStateWithLifecycle(initialValue = null)
    val owner by container.secureStore.has(Secret.OWNER_ACCESS_CODE).collectAsStateWithLifecycle(initialValue = null)
    val canMake = signedIn == true || owner == true
    val needsSignIn = signedIn == false && owner == false
    var started by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<FeedSource?>(null) }
    var removing by remember { mutableStateOf<FeedSource?>(null) }

    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = 16.dp).testTag("sites"),
        contentPadding = PaddingValues(vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Sites you follow", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f))
                Button(onClick = vm::openAddSite, modifier = Modifier.testTag("follow_site")) {
                    Icon(Icons.Outlined.Add, contentDescription = null); Spacer(Modifier.width(6.dp)); Text("Follow")
                }
            }
        }
        item {
            BriefingCard(
                state, canMake, needsSignIn, started,
                onToggle = { on -> vm.setBriefing(on); if (on) onAskNotifications() },
                onPickTime = {
                    TimePickerDialog(context, { _, hour, minute -> vm.setBriefingTime(LocalTime.of(hour, minute)) },
                        state.briefing.time.hour, state.briefing.time.minute, android.text.format.DateFormat.is24HourFormat(context)).show()
                },
                onMakeNow = { BriefingScheduler.runNow(appContext); started = true; onAskNotifications() },
                onOpenLounge = onOpenLounge,
                onSettings = onSettings,
            )
        }
        state.error?.let { item { Text(it, color = MaterialTheme.colorScheme.error) } }
        if (state.sources.isEmpty()) item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Follow the websites you like", style = MaterialTheme.typography.titleMedium)
                    Text("Paste a site's address and Mavis finds its feed. New posts show up here, and can become your daily podcast.")
                    OutlinedButton(onClick = { vm.openAddSite(); vm.siteInput("https://intheworldofai.com/"); vm.findFeed() }) { Text("Try World of AI") }
                }
            }
        }
        items(state.sources, key = { it.id }) { source ->
            SiteCard(
                source, state,
                onOpen = { onOpenSite(source.id) },
                onMode = { vm.setPodcast(source.id, it) },
                onRename = { renaming = source },
                onPause = { vm.setEnabled(source.id, !source.enabled) },
                onRemove = { removing = source },
            )
        }
        if (state.sources.isNotEmpty()) item {
            TextButton(onClick = vm::refresh, enabled = state.loading.isEmpty()) { Text(if (state.loading.isEmpty()) "Check for new posts" else "Checking…") }
        }
    }

    if (state.addOpen) AddSiteDialog(state, vm)
    renaming?.let { source ->
        var name by remember(source.id) { mutableStateOf(source.name) }
        AlertDialog(
            onDismissRequest = { renaming = null },
            title = { Text("Rename site") },
            text = { OutlinedTextField(name, { name = it.take(80) }, label = { Text("Name") }, singleLine = true) },
            confirmButton = { TextButton(enabled = name.isNotBlank(), onClick = { vm.rename(source.id, name); renaming = null }) { Text("Save") } },
            dismissButton = { TextButton(onClick = { renaming = null }) { Text("Cancel") } },
        )
    }
    removing?.let { source ->
        AlertDialog(
            onDismissRequest = { removing = null },
            title = { Text("Stop following ${source.name}?") },
            text = { Text("Its posts leave this page and it won't be in your podcasts. Podcasts already made stay in Podcast Lounge.") },
            confirmButton = { TextButton(onClick = { vm.remove(source.id); removing = null }) { Text("Unfollow") } },
            dismissButton = { TextButton(onClick = { removing = null }) { Text("Keep") } },
        )
    }
}

@Composable
private fun BriefingCard(
    state: SitesUiState, canMake: Boolean, needsSignIn: Boolean, started: Boolean,
    onToggle: (Boolean) -> Unit, onPickTime: () -> Unit, onMakeNow: () -> Unit, onOpenLounge: () -> Unit, onSettings: () -> Unit,
) {
    Card(Modifier.fillMaxWidth().testTag("briefing_card"), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Daily podcast", style = MaterialTheme.typography.titleLarge)
                    Text("Every day, new posts from your sites become a podcast, saved on this phone so it plays offline.",
                        style = MaterialTheme.typography.bodyMedium)
                }
                Switch(state.briefing.enabled, onToggle, Modifier.testTag("briefing_switch"))
            }
            if (needsSignIn) {
                Text("Sign in under Settings → Account so your podcast studio can make these.", color = MaterialTheme.colorScheme.error)
                OutlinedButton(onClick = onSettings) { Text("Open Settings") }
            }
            if (state.briefing.enabled) OutlinedButton(onClick = onPickTime, modifier = Modifier.testTag("briefing_time")) {
                Text("Every day at ${SHORT_TIME.format(state.briefing.time)}")
            }
            when {
                state.pending > 0 -> Text(if (state.pending == 1) "1 podcast is being made. You'll get a notification when it's ready."
                    else "${state.pending} podcasts are being made. You'll get a notification when each is ready.")
                started -> Text("Checking your sites now…")
            }
            state.status?.let { Text("${DateUtils.getRelativeTimeSpanString(it.at)}: ${it.message}", style = MaterialTheme.typography.bodySmall) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(onClick = onMakeNow, enabled = canMake && state.sources.isNotEmpty(), modifier = Modifier.testTag("briefing_now")) { Text("Make one now") }
                TextButton(onClick = onOpenLounge) { Text("Your podcasts") }
            }
            Text("Choose below which sites go in the daily podcast and which get episodes of their own. Your podcast studio needs to be running.",
                style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun SiteCard(
    source: FeedSource, state: SitesUiState,
    onOpen: () -> Unit, onMode: (String) -> Unit, onRename: () -> Unit, onPause: () -> Unit, onRemove: () -> Unit,
) {
    val posts = state.posts[source.id]
    val new = state.newCount(source)
    Card(onClick = onOpen, modifier = Modifier.fillMaxWidth().testTag("site_${source.id}")) {
        Column(Modifier.padding(start = 16.dp, end = 4.dp, top = 12.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(source.name, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(host(source.siteUrl) + if (source.enabled) "" else " · Paused", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (new > 0) NewPill(if (new == 1) "1 new" else "$new new")
                SiteMenu(source, onRename, onPause, onRemove)
            }
            Column(Modifier.padding(end = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                when {
                    source.id in state.loading && posts == null -> LinearProgressIndicator(Modifier.fillMaxWidth())
                    posts.isNullOrEmpty() -> Text(state.notes[source.id] ?: if (source.enabled) "No posts yet." else "Paused. Resume to see new posts.",
                        style = MaterialTheme.typography.bodySmall)
                    else -> {
                        val latest = posts.first()
                        Text("Latest: ${latest.item.title}", style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        latest.published?.let { Text(day(it), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    }
                }
                PodcastModeChips(source.podcast, onMode)
            }
        }
    }
}

@Composable
private fun SiteMenu(source: FeedSource, onRename: () -> Unit, onPause: () -> Unit, onRemove: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) { Icon(Icons.Outlined.MoreVert, contentDescription = "Options for ${source.name}") }
        DropdownMenu(open, { open = false }) {
            DropdownMenuItem({ Text("Rename") }, { open = false; onRename() })
            DropdownMenuItem({ Text(if (source.enabled) "Pause updates" else "Resume updates") }, { open = false; onPause() })
            DropdownMenuItem({ Text("Unfollow") }, { open = false; onRemove() })
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun PodcastModeChips(mode: String, onMode: (String) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        SitePodcast.all.forEach { option ->
            FilterChip(
                selected = mode == option,
                onClick = { onMode(option) },
                label = { Text(when (option) { SitePodcast.BRIEFING -> "In daily podcast"; SitePodcast.OWN -> "Own episodes"; else -> "No podcast" }) },
            )
        }
    }
}

@Composable
private fun NewPill(text: String) {
    Surface(color = MaterialTheme.colorScheme.primary, shape = MaterialTheme.shapes.small) {
        Text(text, Modifier.padding(horizontal = 8.dp, vertical = 2.dp), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onPrimary)
    }
}

@Composable
private fun AddSiteDialog(state: SitesUiState, vm: SitesViewModel) {
    AlertDialog(
        onDismissRequest = vm::closeAddSite,
        title = { Text("Follow a site") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Paste the site's address. Your phone checks the site directly for new posts.")
                OutlinedTextField(state.siteInput, vm::siteInput, singleLine = true, label = { Text("Site address") },
                    placeholder = { Text("intheworldofai.com") }, modifier = Modifier.fillMaxWidth().testTag("site_address"))
                Button(onClick = vm::findFeed, enabled = !state.findingFeed && state.siteInput.isNotBlank()) { Text("Find site") }
                if (state.findingFeed) Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(20.dp)); Spacer(Modifier.width(8.dp)); Text("Looking for its posts…")
                }
                when (val result = state.feedResult) {
                    is DiscoveryResult.Found -> Card {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("Found: ${result.title}", style = MaterialTheme.typography.titleSmall)
                            Text("${result.items} recent posts" + (result.newest?.let { " · newest ${day(it)}" } ?: ""))
                        }
                    }
                    is DiscoveryResult.NotFound -> Text(result.message)
                    null -> Unit
                }
                state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = { if (state.feedResult is DiscoveryResult.Found) TextButton(onClick = vm::addFeed, modifier = Modifier.testTag("confirm_follow")) { Text("Follow") } },
        dismissButton = { TextButton(onClick = vm::closeAddSite) { Text("Cancel") } },
    )
}

/** One followed site's latest posts; opening it marks them as seen. */
@Composable
fun SiteScreen(container: AppContainer, id: String, onBack: () -> Unit, onPodcast: (PodcastDraft) -> Unit, onOpenArticle: (PodcastDraft) -> Unit) {
    val sources by container.feedStore.sources.collectAsStateWithLifecycle(initialValue = null)
    val source = sources?.find { it.id == id }
    if (sources != null && source == null) { LaunchedEffect(Unit) { onBack() }; return }
    if (source == null) return
    val scope = rememberCoroutineScope()
    val since = remember(id) { source.seenSince }
    var load by remember(id) { mutableStateOf<FeedLoad?>(null) }
    LaunchedEffect(id) {
        // A paused site still shows its posts here.
        load = container.feedRepository.load(listOf(source.copy(enabled = true)))
        container.feedStore.markViewed(id)
    }
    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = 16.dp).testTag("site_page"),
        contentPadding = PaddingValues(bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            TextButton(onClick = onBack) { Text("Back to Sites") }
            Text(source.name, style = MaterialTheme.typography.headlineMedium)
            Text(host(source.siteUrl), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        item {
            Text("Podcasts from this site", style = MaterialTheme.typography.titleSmall)
            PodcastModeChips(source.podcast) { mode -> scope.launch { container.feedStore.setPodcast(id, mode) } }
        }
        val current = load
        when {
            current == null -> item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            current.stories.isEmpty() -> item { Text(current.note ?: "This site hasn't posted anything yet.") }
            else -> {
                current.note?.let { note -> item { Text(note, style = MaterialTheme.typography.bodySmall) } }
                item {
                    OutlinedButton(onClick = {
                        val latest = current.stories.take(Briefing.MAX_POSTS_PER_SITE)
                        onPodcast(PodcastDraft.extracted("chapter", "${source.name} · latest posts",
                            Briefing.text("The latest posts from ${source.name}.", latest.map { source to it }, ZoneId.systemDefault())))
                    }, modifier = Modifier.fillMaxWidth()) { Text("Make a podcast of the latest posts") }
                }
                items(current.stories, key = { it.item.id }) { story ->
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                story.published?.let { Text(day(it), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                                if ((story.published ?: 0) > since) NewPill("New")
                            }
                            Text(story.item.title, style = MaterialTheme.typography.titleMedium)
                            if (story.item.summary.isNotBlank()) Text(story.item.summary, style = MaterialTheme.typography.bodyMedium, maxLines = 3, overflow = TextOverflow.Ellipsis)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                TextButton(onClick = { onOpenArticle(PodcastDraft.article(story.item.title, story.item.url)) }) { Text("Read") }
                                TextButton(onClick = { onPodcast(PodcastDraft.article(story.item.title, story.item.url)) }) { Text("Make podcast") }
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun host(url: String) = runCatching { URI(url).host.removePrefix("www.") }.getOrNull() ?: url
private fun day(millis: Long) = POST_DAY.format(Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()))
