package com.mavislibrary.app.ui.sites

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.mavislibrary.app.AppContainer
import com.mavislibrary.app.data.feeds.DiscoveryResult
import com.mavislibrary.app.data.feeds.FeedLoad
import com.mavislibrary.app.data.feeds.FeedSource
import com.mavislibrary.app.data.feeds.FeedStory
import com.mavislibrary.app.data.feeds.SitePodcast
import com.mavislibrary.app.data.podcast.PodcastDraft
import com.mavislibrary.app.data.podcast.PodcastJob
import com.mavislibrary.app.ui.podcast.EpisodeDetailsSheet
import com.mavislibrary.app.ui.podcast.episodeHeadline
import com.mavislibrary.app.ui.podcast.episodeSubtitle
import com.mavislibrary.app.ui.reader.ReaderTarget
import com.mavislibrary.app.work.BriefingScheduler
import java.net.URI
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

private val POST_DAY = DateTimeFormatter.ofPattern("MMM d")

/** Followed sites magazine rack, quick add card, and site actions. */
@Composable
fun SitesScreen(
    container: AppContainer,
    onOpenSite: (String) -> Unit,
    onOpenLounge: () -> Unit,
    onSettings: () -> Unit,
    onAskNotifications: () -> Unit,
    onPlayEpisodes: (List<PodcastJob>) -> Unit = {},
    onOpenAllNew: () -> Unit = {},
    onOpenReader: ((ReaderTarget) -> Unit)? = null,
) {
    val context = LocalContext.current
    val appContext = context.applicationContext
    val vm: SitesViewModel = viewModel(factory = viewModelFactory {
        initializer {
            SitesViewModel(
                container.feedStore,
                container.feedDiscovery,
                container.feedRepository,
                container.briefingStore,
                podsub = container.podsubRepository,
                podcastApi = container.podcastApi(),
                reader = container.readerApi(),
                readState = container.readState,
            ) {
                BriefingScheduler.sync(appContext)
            }
        }
    })
    val state by vm.state.collectAsStateWithLifecycle()
    var renaming by remember { mutableStateOf<FeedSource?>(null) }
    var removing by remember { mutableStateOf<FeedSource?>(null) }

    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp).testTag("sites")) {
        Spacer(Modifier.height(16.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Sites you follow", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f))
            Button(onClick = vm::openAddSite, modifier = Modifier.testTag("follow_site")) {
                Icon(Icons.Outlined.Add, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text("Follow")
            }
        }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = onOpenLounge, modifier = Modifier.fillMaxWidth()) {
            Text("Podcast subscriptions in Lounge")
        }
        state.error?.let {
            Spacer(Modifier.height(8.dp))
            Text(it, color = MaterialTheme.colorScheme.error)
        }

        Spacer(Modifier.height(12.dp))
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 145.dp),
            modifier = Modifier.weight(1f).testTag("sites_grid"),
            contentPadding = PaddingValues(bottom = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (state.upNext.isNotEmpty() || state.sources.isNotEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    UpNextStrip(state.upNext, state.allUnread().size, onPlayEpisodes, onOpenAllNew, onOpenReader)
                }
            }

            // Big + Add site card first
            item {
                AddSiteCard(onClick = vm::openAddSite)
            }

            // Followed sites
            items(state.sources, key = { it.id }) { source ->
                SiteMagazineTile(
                    source = source,
                    state = state,
                    onClick = {
                        vm.selectSite(source.id)
                        onOpenSite(source.id)
                    },
                    onSetTop = { count -> vm.setTopCount(source.id, count) },
                    onSetDelivery = { delivery -> vm.setDelivery(source.id, delivery) },
                    onUnfollow = { removing = source },
                    onMarkAllRead = { vm.markAllRead(source.id) },
                )
            }

            if (state.sources.isNotEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    TextButton(onClick = vm::refresh, enabled = state.loading.isEmpty(), modifier = Modifier.fillMaxWidth()) {
                        Text(if (state.loading.isEmpty()) "Check for new posts" else "Checking…")
                    }
                }
            }
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
            text = { Text("Its posts leave this page. Podcast subscriptions sync when connected. Podcasts already made stay in Podcast Lounge.") },
            confirmButton = { TextButton(onClick = { vm.remove(source.id); removing = null }) { Text("Unfollow") } },
            dismissButton = { TextButton(onClick = { removing = null }) { Text("Keep") } },
        )
    }
}

/** Today's ready briefing episodes plus a shortcut to every unread article. Tapping a card opens what the episode covers. */
@Composable
internal fun UpNextStrip(
    episodes: List<PodcastJob>, unread: Int, onPlay: (List<PodcastJob>) -> Unit, onOpenAllNew: () -> Unit,
    onOpenReader: ((ReaderTarget) -> Unit)? = null, now: Instant = Instant.now(), zone: ZoneId = ZoneId.systemDefault(),
) {
    var details by remember { mutableStateOf<PodcastJob?>(null) }
    Column(Modifier.fillMaxWidth().testTag("up_next"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Up next", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            AssistChip(onClick = onOpenAllNew, label = { Text("All new · $unread") }, modifier = Modifier.testTag("all_new_chip"))
        }
        if (episodes.isNotEmpty()) LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            items(episodes, key = { it.id }) { job ->
                val headline = episodeHeadline(job)
                Card(onClick = { details = job }, modifier = Modifier.width(272.dp).testTag("up_next_${job.id}"),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(headline, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.testTag("up_next_headline_${job.id}"))
                            episodeSubtitle(job, now, zone).takeIf(String::isNotEmpty)?.let {
                                Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.testTag("up_next_subtitle_${job.id}"))
                            }
                        }
                        FilledTonalIconButton(onClick = { onPlay(listOf(job)) }, modifier = Modifier.testTag("play_up_next_${job.id}")) {
                            Icon(Icons.Outlined.PlayArrow, contentDescription = "Play $headline")
                        }
                    }
                }
            }
        }
    }
    details?.let { job -> EpisodeDetailsSheet(job, onDismiss = { details = null }, onPlay = { onPlay(listOf(job)) }, onOpenReader = onOpenReader) }
}

/** Unread articles from every followed site, newest first. */
@Composable
fun AllNewScreen(container: AppContainer, onBack: () -> Unit, onOpenReader: (ReaderTarget) -> Unit) {
    val vm: SitesViewModel = viewModel(factory = viewModelFactory {
        initializer {
            SitesViewModel(container.feedStore, container.feedDiscovery, container.feedRepository, container.briefingStore,
                reader = container.readerApi(), readState = container.readState)
        }
    })
    val state by vm.state.collectAsStateWithLifecycle()
    val unread = state.allUnread()
    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = 16.dp).testTag("all_new"),
        contentPadding = PaddingValues(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back to Sites") }
                Text("All new · ${unread.size}", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
            }
        }
        if (state.loading.isNotEmpty() && unread.isEmpty()) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
        else if (unread.isEmpty()) item { Text("You're all caught up.") }
        items(unread, key = { (s, story) -> "${s.id}/${story.item.id}" }) { (source, story) ->
            Card(onClick = { onOpenReader(ReaderTarget(story.item.title, story.item.url, source.name, source.id, story.item.id)) },
                modifier = Modifier.fillMaxWidth().testTag("all_new_${story.item.id}")) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(source.name, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary,
                            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        story.published?.let { Text(day(it), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    }
                    Text(story.item.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

/** Big + Add site card first on the magazine shelf. */
@Composable
private fun AddSiteCard(onClick: () -> Unit) {
    Card(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .height(190.dp)
            .testTag("add_site_card"),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
        border = BorderStroke(1.5.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(16.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primaryContainer,
                modifier = Modifier.size(52.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        Icons.Outlined.Add,
                        contentDescription = "Add site",
                        modifier = Modifier.size(30.dp),
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            Text("Add site", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text("Follow a feed", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Magazine rack tile with initial/cover, unread badge, and overflow options. */
@Composable
private fun SiteMagazineTile(
    source: FeedSource,
    state: SitesUiState,
    onClick: () -> Unit,
    onSetTop: (Int) -> Unit,
    onSetDelivery: (String) -> Unit,
    onUnfollow: () -> Unit,
    onMarkAllRead: () -> Unit,
) {
    val unread = state.unreadCount(source)
    val rule = state.siteRules[source.id]
    val isOff = rule?.mode == "off" || source.podcast == SitePodcast.OFF
    val currentTop = if (isOff) 0 else (rule?.count ?: source.topCount)
    val currentDelivery = rule?.delivery ?: (if (source.podcast == SitePodcast.OWN) "separate" else "briefing")
    var menuOpen by remember { mutableStateOf(false) }

    Card(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .height(190.dp)
            .testTag("site_${source.id}"),
    ) {
        Column(Modifier.fillMaxSize()) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .background(tileColor(source.name))
                    .padding(8.dp)
            ) {
                if (unread > 0) {
                    Box(Modifier.align(Alignment.TopStart).testTag("unread_${source.id}")) {
                        NewPill("$unread unread")
                    }
                }
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
                    modifier = Modifier.size(54.dp).align(Alignment.Center),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(
                            text = source.name.firstOrNull()?.uppercase() ?: "S",
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
                Box(Modifier.align(Alignment.TopEnd)) {
                    IconButton(
                        onClick = { menuOpen = true },
                        modifier = Modifier.size(32.dp).testTag("site_menu_${source.id}"),
                    ) {
                        Icon(Icons.Outlined.MoreVert, contentDescription = "Options for ${source.name}")
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text("Top: Off" + if (isOff) " ✓" else "") },
                            onClick = { menuOpen = false; onSetTop(0) },
                        )
                        listOf(1, 2, 3, 5).forEach { n ->
                            DropdownMenuItem(
                                text = { Text("Top $n" + if (!isOff && currentTop == n) " ✓" else "") },
                                onClick = { menuOpen = false; onSetTop(n) },
                            )
                        }
                        HorizontalDivider()
                        DropdownMenuItem(
                            text = { Text("Briefing" + if (currentDelivery == "briefing") " ✓" else "") },
                            onClick = { menuOpen = false; onSetDelivery("briefing") },
                        )
                        DropdownMenuItem(
                            text = { Text("Separate" + if (currentDelivery == "separate") " ✓" else "") },
                            onClick = { menuOpen = false; onSetDelivery("separate") },
                        )
                        HorizontalDivider()
                        DropdownMenuItem(
                            text = { Text("Mark all read") },
                            enabled = unread > 0,
                            onClick = { menuOpen = false; onMarkAllRead() },
                        )
                        HorizontalDivider()
                        DropdownMenuItem(
                            text = { Text("Unfollow", color = MaterialTheme.colorScheme.error) },
                            onClick = { menuOpen = false; onUnfollow() },
                        )
                    }
                }
            }
            Column(Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
                Text(
                    source.name,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    host(source.siteUrl),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun tileColor(name: String): Color {
    val colors = listOf(
        MaterialTheme.colorScheme.primaryContainer,
        MaterialTheme.colorScheme.secondaryContainer,
        MaterialTheme.colorScheme.tertiaryContainer,
        MaterialTheme.colorScheme.surfaceVariant,
    )
    val idx = (name.hashCode() and 0x7fffffff) % colors.size
    return colors[idx]
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
                Text("Paste the site's address. Mavis finds its feed or watches the page for new posts.")
                OutlinedTextField(
                    state.siteInput,
                    vm::siteInput,
                    singleLine = true,
                    label = { Text("Site address") },
                    placeholder = { Text("intheworldofai.com") },
                    modifier = Modifier.fillMaxWidth().testTag("site_address")
                )
                Button(onClick = vm::findFeed, enabled = !state.findingFeed && state.siteInput.isNotBlank()) {
                    Text("Find site")
                }
                if (state.findingFeed) Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Looking for its posts…")
                }
                when (val result = state.feedResult) {
                    is DiscoveryResult.Found -> Card {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(if (state.preview?.source == "page") "No feed here — Mavis will watch this page" else "Found: ${result.title}", style = MaterialTheme.typography.titleSmall)
                            state.preview?.posts?.take(3)?.forEach { Text(it.title, style = MaterialTheme.typography.bodyMedium) }
                            Text("${result.items} recent posts" + (result.newest?.let { " · newest ${day(it)}" } ?: ""))
                        }
                    }
                    is DiscoveryResult.NotFound -> Text(result.message)
                    null -> Unit
                }
                state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            if (state.feedResult is DiscoveryResult.Found) {
                TextButton(onClick = vm::addFeed, modifier = Modifier.testTag("confirm_follow")) { Text("Follow") }
            }
        },
        dismissButton = { TextButton(onClick = vm::closeAddSite) { Text("Cancel") } },
    )
}

/** One followed site's articles immediately available; includes multi-select combined podcast creation. */
@Composable
fun SiteScreen(
    container: AppContainer,
    id: String,
    onBack: () -> Unit,
    onPodcast: (PodcastDraft) -> Unit,
    onOpenReader: (ReaderTarget) -> Unit,
) {
    val context = LocalContext.current
    val appContext = context.applicationContext
    val vm: SitesViewModel = viewModel(factory = viewModelFactory {
        initializer {
            SitesViewModel(
                container.feedStore,
                container.feedDiscovery,
                container.feedRepository,
                container.briefingStore,
                podsub = container.podsubRepository,
                podcastApi = container.podcastApi(),
                reader = container.readerApi(),
                readState = container.readState,
            ) {
                BriefingScheduler.sync(appContext)
            }
        }
    })
    val state by vm.state.collectAsStateWithLifecycle()
    val source = state.sources.find { it.id == id }
    if (state.sources.isNotEmpty() && source == null) {
        LaunchedEffect(Unit) { onBack() }
        return
    }
    if (source == null) return

    val since = remember(id) { source.seenSince }
    val stories = state.posts[id]
    val loading = id in state.loading && stories == null

    LaunchedEffect(id) {
        vm.selectSite(id)
    }

    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = 16.dp).testTag("site_page"),
        contentPadding = PaddingValues(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack, modifier = Modifier.testTag("back_button")) {
                    Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back to Sites")
                }
                Spacer(Modifier.width(4.dp))
                Column(Modifier.weight(1f)) {
                    Text(source.name, style = MaterialTheme.typography.headlineSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(host(source.siteUrl), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                var siteMenu by remember { mutableStateOf(false) }
                Box {
                    IconButton(onClick = { siteMenu = true }, modifier = Modifier.testTag("site_page_menu")) {
                        Icon(Icons.Outlined.MoreVert, contentDescription = "Options for ${source.name}")
                    }
                    DropdownMenu(expanded = siteMenu, onDismissRequest = { siteMenu = false }) {
                        DropdownMenuItem(
                            text = { Text("Mark all read") },
                            enabled = state.unreadCount(source) > 0,
                            onClick = { siteMenu = false; vm.markAllRead(source.id) },
                            modifier = Modifier.testTag("mark_all_read"),
                        )
                    }
                }
            }
        }

        // Multi-select banner when items selected
        if (!stories.isNullOrEmpty()) {
            val selectedStories = stories.filter { it.item.id in state.selectedArticles }
            val combinedKey = "combined_${source.id}"
            val isCombinedInFlight = combinedKey in state.inFlightRequests
            val combinedQueued = state.queuedStatus[combinedKey]

            item {
                Card(
                    modifier = Modifier.fillMaxWidth().testTag("multi_select_bar"),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                ) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                if (selectedStories.isEmpty()) "Select articles to combine" else "${selectedStories.size} selected",
                                style = MaterialTheme.typography.titleMedium,
                                modifier = Modifier.weight(1f),
                            )
                            if (selectedStories.isNotEmpty()) {
                                TextButton(onClick = vm::clearArticleSelection) { Text("Clear") }
                            }
                            TextButton(onClick = { vm.selectAllArticles(source.id) }) { Text("Select all") }
                        }
                        if (selectedStories.isNotEmpty()) {
                            Button(
                                onClick = { vm.makeCombinedPodcast(source, selectedStories) },
                                enabled = !isCombinedInFlight && combinedQueued == null,
                                modifier = Modifier.fillMaxWidth().testTag("make_combined_podcast"),
                            ) {
                                Text(
                                    when {
                                        isCombinedInFlight -> "Queueing combined…"
                                        combinedQueued != null -> combinedQueued
                                        else -> "Make combined podcast (${selectedStories.size})"
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }

        state.error?.let { err ->
            item { Text(err, color = MaterialTheme.colorScheme.error) }
        }

        when {
            loading -> item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            stories.isNullOrEmpty() -> item {
                Text(state.notes[source.id] ?: if (source.enabled) "No posts yet." else "Paused. Resume to see new posts.")
            }
            else -> {
                items(stories, key = { it.item.id }) { story ->
                    val isSelected = story.item.id in state.selectedArticles
                    val isInFlight = story.item.id in state.inFlightRequests
                    val queuedStatus = state.queuedStatus[story.item.id]

                    Card(Modifier.fillMaxWidth().alpha(if (state.isRead(source.id, story)) 0.55f else 1f).testTag("story_${story.item.id}")) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(
                                    checked = isSelected,
                                    onCheckedChange = { vm.toggleArticleSelection(story.item.id) },
                                    modifier = Modifier.testTag("select_story_${story.item.id}"),
                                )
                                story.published?.let {
                                    Text(day(it), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                Spacer(Modifier.width(8.dp))
                                if ((story.published ?: 0) > since) NewPill("New")
                            }
                            Text(story.item.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                            val snippet = story.item.summary.ifBlank { story.body }.take(250)
                            if (snippet.isNotBlank()) {
                                Text(snippet, style = MaterialTheme.typography.bodyMedium, maxLines = 3, overflow = TextOverflow.Ellipsis)
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                TextButton(
                                    onClick = { onOpenReader(ReaderTarget(story.item.title, story.item.url, source.name, source.id, story.item.id)) },
                                    modifier = Modifier.testTag("read_story_${story.item.id}"),
                                ) {
                                    Text("Read")
                                }
                                Button(
                                    onClick = { vm.makeArticlePodcast(source, story) },
                                    enabled = !isInFlight && queuedStatus == null,
                                    modifier = Modifier.testTag("podcast_story_${story.item.id}"),
                                ) {
                                    Text(
                                        when {
                                            isInFlight -> "Queueing…"
                                            queuedStatus != null -> queuedStatus
                                            else -> "Make podcast"
                                        }
                                    )
                                }
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
