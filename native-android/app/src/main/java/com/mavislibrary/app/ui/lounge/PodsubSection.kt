package com.mavislibrary.app.ui.lounge

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.mavislibrary.app.data.podsub.*
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID

fun subscriptionArrival(at: String?, schedule: SubscriptionSchedule, now: Instant = Instant.now()): String {
    if (!schedule.enabled) return "Delivery is off"
    val time = runCatching { Instant.parse(at).plusSeconds(3600).atZone(ZoneId.of(schedule.timezone)) }.getOrNull()
        ?: return "Ready by ${schedule.readyBy}"
    val today = now.atZone(time.zone).toLocalDate()
    val day = when (time.toLocalDate()) { today -> if (time.hour >= 17) "Tonight" else "Today"; today.plusDays(1) -> "Tomorrow"; else -> time.format(DateTimeFormatter.ofPattern("EEE", Locale.US)) }
    return "$day ${time.format(DateTimeFormatter.ofPattern("h:mm a", Locale.US))}"
}

const val PODCAST_PAGE_MAIN = "main"
const val PODCAST_PAGE_SITES = "sites"
const val PODCAST_PAGE_DEVOTIONALS = "devotionals"

/**
 * Settings → Podcasts (BRIEF-085 F): schedules, retention, usage, briefing sites and devotionals moved out of
 * the Lounge. Each change saves right away; only typed site names wait for "Save changes".
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun PodcastSettingsContent(state: PodsubUiState, vm: PodsubViewModel, onBack: () -> Unit, onStartDaily: (String) -> Unit,
                           startPage: String = PODCAST_PAGE_MAIN, styleLabel: String? = null, styleSection: @Composable () -> Unit = {}) {
    var page by rememberSaveable { mutableStateOf(startPage) }
    var timeFor by remember { mutableStateOf<String?>(null) }
    var add by remember { mutableStateOf<String?>(null) }
    var editing by remember { mutableStateOf<DevotionalRule?>(null) }
    var editSite by remember { mutableStateOf<String?>(null) }
    var rankOpen by remember { mutableStateOf(false) }
    var styleOpen by rememberSaveable { mutableStateOf(false) }
    val doc = state.document
    val ready = !state.busy
    BackHandler(enabled = page != PODCAST_PAGE_MAIN) { page = PODCAST_PAGE_MAIN }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 24.dp).testTag("podcast_settings_page"),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { if (page == PODCAST_PAGE_MAIN) onBack() else page = PODCAST_PAGE_MAIN }, modifier = Modifier.size(48.dp)) {
                Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back")
            }
            Text(if (page == PODCAST_PAGE_MAIN) "Settings" else "Podcasts", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(when (page) { PODCAST_PAGE_SITES -> "Briefing sites"; PODCAST_PAGE_DEVOTIONALS -> "Devotionals"; else -> "Podcasts" },
            Modifier.semantics { heading() }, style = MaterialTheme.typography.headlineLarge)
        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        when (page) {
            PODCAST_PAGE_SITES -> if (doc != null) {
                if (doc.sites.isEmpty()) Text("Add a website or follow one in Sites.")
                doc.sites.sortedBy { it.priority }.forEachIndexed { index, site ->
                    SubscriptionCard(site.name, if (site.delivery == "briefing") "Part of your daily briefing" else "A podcast for each story",
                        subscriptionArrival(state.nextRunAt, doc.schedule), state.lastRun?.episodes?.lastOrNull { site.id in it.siteIds }?.title, site.mode == "top", ready,
                        onToggle = { enabled -> vm.commit { d -> d.copy(sites = d.sites.map { if (it.id == site.id) it.copy(mode = if (enabled) "top" else "off", count = if (enabled) it.count ?: 1 else null) else it }) } },
                        onEdit = { editSite = if (editSite == site.id) null else site.id },
                        onRemove = { vm.commit { d -> d.copy(sites = d.sites.filterNot { it.id == site.id }) } })
                    if (editSite == site.id) Card {
                        Column(Modifier.padding(12.dp)) {
                            OutlinedTextField(site.name, { name -> vm.edit { d -> d.copy(sites = d.sites.map { if (it.id == site.id) it.copy(name = name) else it }) } }, label = { Text("Name") })
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                (1..5).forEach { n -> FilterChip(site.count == n, { vm.commit { d -> d.copy(sites = d.sites.map { if (it.id == site.id) it.copy(count = n, mode = "top") else it }) } }, { Text("Top $n") }) }
                            }
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                listOf("briefing" to "Briefing", "separate" to "Separate").forEach { (value, label) -> FilterChip(site.delivery == value, { vm.commit { d -> d.copy(sites = d.sites.map { if (it.id == site.id) it.copy(delivery = value) else it }) } }, { Text(label) }) }
                            }
                            Row { TextButton({ vm.move(site.id, -1); vm.save() }, enabled = index > 0) { Text("Move up") }; TextButton({ vm.move(site.id, 1); vm.save() }, enabled = index < doc.sites.lastIndex) { Text("Move down") } }
                        }
                    }
                }
                OutlinedButton({ vm.clearPreview(); add = "website" }, enabled = ready, modifier = Modifier.heightIn(min = 48.dp)) { Text("Add a site") }
                state.lastRun?.skipped?.forEach { Text("${it.siteId}: ${it.reason.replace('_', ' ')}", style = MaterialTheme.typography.bodySmall) }
                if (state.dirty) Button({ vm.save() }, enabled = ready, modifier = Modifier.fillMaxWidth()) { Text("Save changes") }
            }
            PODCAST_PAGE_DEVOTIONALS -> if (doc != null) {
                if (doc.devotionals.isEmpty()) Text("A Bible lesson for your daily walk.")
                doc.devotionals.forEach { d ->
                    SubscriptionCard(d.name, if (d.kind == "site") "Bible lesson from today's devotion" else "Daily Bible lesson on this topic",
                        subscriptionArrival(state.nextDevotionalAt, doc.devotionalSchedule), state.lastDevotionals[d.id]?.title, d.enabled, ready,
                        onToggle = { enabled -> vm.commit { doc -> doc.copy(devotionals = doc.devotionals.map { if (it.id == d.id) it.copy(enabled = enabled) else it }) } },
                        onEdit = { editing = d; vm.clearPreview(); add = if (d.kind == "site") "devotional" else "topic" },
                        onRemove = { vm.commit { doc -> doc.copy(devotionals = doc.devotionals.filterNot { it.id == d.id }) } })
                }
                Button({ onStartDaily(DAILY_SITE) }, enabled = ready && doc.devotionals.size < 5, modifier = Modifier.heightIn(min = 48.dp)) { Text("Start a daily devotional") }
                OutlinedButton({ vm.clearPreview(); editing = null; add = "devotional" }, enabled = ready && doc.devotionals.size < 5, modifier = Modifier.heightIn(min = 48.dp)) { Text("Add a site") }
                OutlinedButton({ vm.clearPreview(); editing = null; add = "topic" }, enabled = ready && doc.devotionals.size < 5, modifier = Modifier.heightIn(min = 48.dp)) { Text("Add a topic") }
            }
            else -> {
                Text(state.meter, style = MaterialTheme.typography.bodyLarge)
                state.usage?.takeIf { it.limit > 0 }?.let { LinearProgressIndicator(progress = { (it.used.toFloat() / it.limit).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth()) }
                if (doc != null) {
                    ScheduleCard("Briefing delivery", doc.schedule, ready, { timeFor = "briefing" }) { s -> vm.commit { it.copy(schedule = s) } }
                    ScheduleCard("Devotional delivery", doc.devotionalSchedule, ready, { timeFor = "devotional" }) { s -> vm.commit { it.copy(devotionalSchedule = s) } }
                    var keep by remember(doc.keepDays) { mutableFloatStateOf(doc.keepDays.toFloat()) }
                    Text("Keep episodes for ${keep.toInt()} days")
                    Slider(keep, { keep = it }, valueRange = 1f..30f, steps = 28, enabled = ready,
                        onValueChangeFinished = { vm.commit { it.copy(keepDays = keep.toInt()) } })
                    HorizontalDivider()
                    NavRow("Briefing sites", doc.sites.size.toString()) { page = PODCAST_PAGE_SITES }
                    NavRow("Devotionals", doc.devotionals.size.toString()) { page = PODCAST_PAGE_DEVOTIONALS }
                    NavRow("Stories first", rankLabels[doc.rank] ?: doc.rank) { rankOpen = true }
                    ToggleRow("Use backup voices if needed", doc.engine == "auto", ready) { on -> vm.commit { it.copy(engine = if (on) "auto" else "notebooklm") } }
                    HorizontalDivider()
                    ToggleRow("Telegram notifications", doc.notify.telegram, ready) { on -> vm.commit { it.copy(notify = SubscriptionNotify(on)) } }
                    HorizontalDivider()
                }
                NavRow("Default style and length", styleLabel, expanded = styleOpen) { styleOpen = !styleOpen }
                if (styleOpen) styleSection()
                if (doc != null) TextButton({ vm.runNow() }, enabled = ready && !state.dirty, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text("Run briefing now", style = MaterialTheme.typography.titleMedium)
                }
            }
        }
    }
    if (timeFor != null && doc != null) {
        val devotional = timeFor == "devotional"
        val schedule = if (devotional) doc.devotionalSchedule else doc.schedule
        ReadyByDialog(schedule, onDismiss = { timeFor = null }) { readyBy ->
            val next = schedule.copy(enabled = true, readyBy = readyBy)
            vm.commit { if (devotional) it.copy(devotionalSchedule = next) else it.copy(schedule = next) }; timeFor = null
        }
    }
    if (rankOpen && doc != null) AlertDialog(onDismissRequest = { rankOpen = false }, title = { Text("Stories first") }, text = {
        Column { rankLabels.forEach { (rank, label) ->
            Row(Modifier.fillMaxWidth().selectable(doc.rank == rank, role = Role.RadioButton) { vm.commit { it.copy(rank = rank) }; rankOpen = false }.heightIn(min = 48.dp),
                verticalAlignment = Alignment.CenterVertically) { RadioButton(doc.rank == rank, null); Spacer(Modifier.width(12.dp)); Text(label) }
        } }
    }, confirmButton = { TextButton({ rankOpen = false }) { Text("Close") } })
    if (add != null) ModalBottomSheet(onDismissRequest = { add = null; editing = null }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        SubscriptionEditor(add!!, editing, state, vm) { add = null; editing = null }
        Spacer(Modifier.height(24.dp))
    }
}

private val rankLabels = linkedMapOf("newest" to "Newest", "important" to "Most important")

@Composable
private fun NavRow(label: String, value: String?, expanded: Boolean? = null, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onClick).heightIn(min = 56.dp).testTag("podcast_row_$label"),
        verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        value?.let { Text(it, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        Spacer(Modifier.width(8.dp))
        Icon(when (expanded) { true -> Icons.Outlined.ExpandLess; false -> Icons.Outlined.ExpandMore; null -> Icons.AutoMirrored.Outlined.KeyboardArrowRight },
            contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ReadyByDialog(schedule: SubscriptionSchedule, onDismiss: () -> Unit, onSet: (String) -> Unit) {
    val time = rememberTimePickerState(schedule.readyBy.substringBefore(':').toInt(), schedule.readyBy.substringAfter(':').toInt(), false)
    Dialog(onDismiss) { Surface(shape = MaterialTheme.shapes.extraLarge) { Column(Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text("Ready by"); TimePicker(time)
        Row { TextButton(onDismiss) { Text("Cancel") }; TextButton({ onSet(String.format(Locale.ROOT, "%02d:%02d", time.hour, time.minute)) }) { Text("Set time") } }
    } } }
}

@Composable
private fun SubscriptionCard(name: String, description: String, arrival: String, last: String?, checked: Boolean, enabled: Boolean,
                             onToggle: (Boolean) -> Unit, onEdit: () -> Unit, onRemove: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) { Text(name, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium); Switch(checked, onToggle, enabled = enabled)
            Box { IconButton({ menu = true }, enabled = enabled) { Icon(Icons.Outlined.MoreVert, contentDescription = "Options for $name") }; DropdownMenu(menu, { menu = false }) {
                DropdownMenuItem({ Text("Edit") }, { menu = false; onEdit() }); DropdownMenuItem({ Text("Remove") }, { menu = false; onRemove() })
            } }
        }
        Text(description, style = MaterialTheme.typography.bodyMedium)
        Text(if (checked) arrival else "Paused", style = MaterialTheme.typography.bodySmall)
        last?.let { Text("Last episode: $it", style = MaterialTheme.typography.bodySmall) }
    } }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ScheduleCard(title: String, schedule: SubscriptionSchedule, enabled: Boolean, time: () -> Unit, change: (SubscriptionSchedule) -> Unit) {
    Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp)) {
        ToggleRow(title, schedule.enabled, enabled, MaterialTheme.typography.titleMedium) { change(schedule.copy(enabled = it)) }
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(time, enabled = enabled, modifier = Modifier.testTag(title), contentPadding = PaddingValues(horizontal = 0.dp)) {
                Text("Ready by ${readyByLabel(schedule.readyBy)}", style = MaterialTheme.typography.titleMedium)
            }
            Spacer(Modifier.weight(1f))
            Text(schedule.timezone, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) { subscriptionDays.forEach { day ->
            FilterChip(day in schedule.days, { change(schedule.copy(days = subscriptionDays.filter { if (it == day) it !in schedule.days else it in schedule.days })) }, { Text(day.replaceFirstChar { it.titlecase() }) }, enabled = enabled)
        } }
    } }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SubscriptionEditor(kind: String, editing: DevotionalRule?, state: PodsubUiState, vm: PodsubViewModel, close: () -> Unit) {
    var value by remember { mutableStateOf(if (kind == "topic") editing?.topic.orEmpty() else editing?.url.orEmpty()) }
    var name by remember { mutableStateOf(editing?.name.orEmpty()) }
    var surprise by remember { mutableStateOf(editing?.surprise ?: false) }
    var error by remember { mutableStateOf<String?>(null) }
    val topic = kind == "topic"
    Column(Modifier.verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(if (topic) "Daily Bible topic" else if (kind == "devotional") "Devotional site" else "Website", style = MaterialTheme.typography.titleLarge)
        if (kind == "devotional") OutlinedButton({ value = "https://todaydevotional.com/daily-devotional-library"; name = "Today Daily Devotional"; vm.preview(value, true) }) { Text("Today Daily Devotional") }
        OutlinedTextField(value, { value = it.take(if (topic) 120 else 2048); vm.clearPreview() }, modifier = Modifier.fillMaxWidth(), label = { Text(if (topic) "Topic" else "Website link") }, enabled = !surprise && !state.busy)
        if (topic) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) { listOf("Anxiety", "Marriage", "Fatherhood", "Forgiveness", "Purpose", "Trusting God", "Gratitude", "Grief").forEach { suggestion ->
                SuggestionChip({ value = suggestion; surprise = false }, { Text(suggestion) })
            } }
            ToggleRow("Surprise me", surprise, !state.busy) { surprise = it }
        } else {
            OutlinedTextField(name, { name = it.take(200) }, label = { Text("Name") })
            Button({ vm.preview(value.trim(), kind == "devotional") }, enabled = !state.busy && value.isNotBlank()) { Text("Preview") }
            if (state.previewUrl == value.trim()) {
                state.devotionalPreview?.let { Text(it.title); Text(it.reference) }
                state.websitePreview?.let { Text(it.title); it.posts.take(3).forEach { p -> Text(p.title) } }
            }
        }
        (error ?: state.error)?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        if (state.busy) LinearProgressIndicator()
        Button({
            try {
                if (kind == "website") vm.addWebsite(name)
                else vm.addDevotional(DevotionalRule(editing?.id ?: UUID.randomUUID().toString(), if (topic) "topic" else "site",
                    if (topic) if (surprise) "Surprise me" else value.trim() else name.ifBlank { state.devotionalPreview?.title ?: "Devotional" },
                    url = if (topic) null else value.trim(), topic = if (topic) value.trim() else null, surprise = surprise, enabled = editing?.enabled ?: true))
                vm.save()   // Add/Update is the commit: never leave a new subscription unsaved behind a second button.
                close()
            } catch (e: Exception) { error = e.message ?: "Check the subscription details." }
        }, enabled = !state.busy && (topic && (surprise || value.isNotBlank()) || !topic && state.previewUrl == value.trim() && (state.devotionalPreview != null || state.websitePreview != null))) { Text(if (editing == null) "Add subscription" else "Update subscription") }
    }
}
@Composable
private fun ToggleRow(label: String, checked: Boolean, enabled: Boolean, style: androidx.compose.ui.text.TextStyle = MaterialTheme.typography.bodyLarge, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(checked, enabled = enabled, role = Role.Switch, onValueChange = onChange),
        verticalAlignment = Alignment.CenterVertically) { Text(label, Modifier.weight(1f), style = style); Switch(checked, null, enabled = enabled) }
}
