package com.mavislibrary.app.ui.lounge

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.mavislibrary.app.data.podcast.PodcastJob
import com.mavislibrary.app.data.podsub.*
import com.mavislibrary.app.ui.podcast.episodeSubtitle
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

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun PodsubSection(state: PodsubUiState, vm: PodsubViewModel, onDetails: (PodcastJob) -> Unit = {}, library: List<PodcastJob> = state.episodes, onPlay: (List<PodcastJob>) -> Unit) {
    var timeFor by remember { mutableStateOf<String?>(null) }
    var add by remember { mutableStateOf<String?>(null) }
    var editing by remember { mutableStateOf<DevotionalRule?>(null) }
    var editSite by remember { mutableStateOf<String?>(null) }
    val doc = state.document
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Subscriptions", Modifier.weight(1f), style = MaterialTheme.typography.headlineSmall)
            Button(onClick = { vm.clearPreview(); add = "pick" }, enabled = doc != null && !state.busy) { Text("Add") }
        }
        Text(state.meter, style = MaterialTheme.typography.bodySmall)
        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (doc != null) {
            Text("Daily briefing", style = MaterialTheme.typography.titleLarge)
            if (doc.sites.isEmpty()) Text("Add a website or follow one in Sites.")
            doc.sites.sortedBy { it.priority }.forEachIndexed { index, site ->
                val last = state.lastRun?.episodes?.lastOrNull { site.id in it.siteIds }
                SubscriptionCard(site.name, if (site.delivery == "briefing") "Part of your daily briefing" else "A podcast for each story",
                    subscriptionArrival(state.nextRunAt, doc.schedule), last?.title,
                    library.find { it.id == last?.jobId && it.status == "ready" }, site.mode == "top", !state.busy,
                    onToggle = { enabled -> vm.edit { d -> d.copy(sites = d.sites.map { if (it.id == site.id) it.copy(mode = if (enabled) "top" else "off", count = if (enabled) it.count ?: 1 else null) else it }) } },
                    onEdit = { editSite = if (editSite == site.id) null else site.id },
                    onRemove = { vm.edit { d -> d.copy(sites = d.sites.filterNot { it.id == site.id }) } }, onPlay = { onPlay(listOf(it)) })
                if (editSite == site.id) Card {
                    Column(Modifier.padding(12.dp)) {
                        OutlinedTextField(site.name, { name -> vm.edit { d -> d.copy(sites = d.sites.map { if (it.id == site.id) it.copy(name = name) else it }) } }, label = { Text("Name") })
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            (1..5).forEach { n -> FilterChip(site.count == n, { vm.edit { d -> d.copy(sites = d.sites.map { if (it.id == site.id) it.copy(count = n, mode = "top") else it }) } }, { Text("Top $n") }) }
                        }
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            listOf("briefing" to "Briefing", "separate" to "Separate").forEach { (value, label) -> FilterChip(site.delivery == value, { vm.edit { d -> d.copy(sites = d.sites.map { if (it.id == site.id) it.copy(delivery = value) else it }) } }, { Text(label) }) }
                        }
                        Row { TextButton({ vm.move(site.id, -1) }, enabled = index > 0) { Text("Move up") }; TextButton({ vm.move(site.id, 1) }, enabled = index < doc.sites.lastIndex) { Text("Move down") } }
                    }
                }
            }
            Text("Devotionals", style = MaterialTheme.typography.titleLarge)
            if (doc.devotionals.isEmpty()) Text("A Bible lesson for your daily walk.")
            doc.devotionals.forEach { d ->
                val last = state.lastDevotionals[d.id]
                SubscriptionCard(d.name, if (d.kind == "site") "Bible lesson from today's devotion" else "Daily Bible lesson on this topic",
                    subscriptionArrival(state.nextDevotionalAt, doc.devotionalSchedule), last?.title,
                    library.find { it.id == last?.jobId && it.status == "ready" }, d.enabled, !state.busy,
                    onToggle = { enabled -> vm.edit { doc -> doc.copy(devotionals = doc.devotionals.map { if (it.id == d.id) it.copy(enabled = enabled) else it }) } },
                    onEdit = { editing = d; vm.clearPreview(); add = if (d.kind == "site") "devotional" else "topic" },
                    onRemove = { vm.edit { doc -> doc.copy(devotionals = doc.devotionals.filterNot { it.id == d.id }) } }, onPlay = { onPlay(listOf(it)) })
            }
            Text("Delivery", style = MaterialTheme.typography.titleLarge)
            ScheduleCard("Briefing delivery", doc.schedule, !state.busy, { timeFor = "briefing" }) { s -> vm.edit { it.copy(schedule = s) } }
            ScheduleCard("Devotional delivery", doc.devotionalSchedule, !state.busy, { timeFor = "devotional" }) { s -> vm.edit { it.copy(devotionalSchedule = s) } }
            Text("Keep episodes for ${doc.keepDays} days")
            Slider(doc.keepDays.toFloat(), { n -> vm.edit { it.copy(keepDays = n.toInt()) } }, valueRange = 1f..30f, steps = 28, enabled = !state.busy)
            ToggleRow("Use backup voices if needed", doc.engine == "auto", !state.busy) { on -> vm.edit { it.copy(engine = if (on) "auto" else "notebooklm") } }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("newest" to "Newest stories", "important" to "Most important").forEach { (rank, label) -> FilterChip(doc.rank == rank, { vm.edit { it.copy(rank = rank) } }, { Text(label) }) }
            }
            ToggleRow("Telegram notifications", doc.notify.telegram, !state.busy) { on -> vm.edit { it.copy(notify = SubscriptionNotify(on)) } }
            Button({ vm.save() }, enabled = state.dirty && !state.busy, modifier = Modifier.fillMaxWidth()) { Text("Save subscriptions") }
        }
        HorizontalDivider()
        Row(verticalAlignment = Alignment.CenterVertically) { Text("Today", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge); TextButton({ vm.refresh() }, enabled = !state.busy) { Text("Refresh") } }
        val ready = state.episodes.filter { it.status == "ready" }
        Button({ onPlay(ready) }, enabled = ready.isNotEmpty()) { Text("Play all (${ready.size})") }
        state.episodes.forEach { job -> Card(onClick = { onDetails(job) }) { Column(Modifier.padding(12.dp)) { Text(job.title); Text(if (job.terminal) episodeSubtitle(job) else stageLabel(job)); if (job.status == "ready") TextButton({ onPlay(listOf(job)) }) { Text("Play") } } } }
        Button({ vm.runNow() }, enabled = doc != null && !state.busy && !state.dirty) { Text("Run briefing now") }
        state.lastRun?.skipped?.forEach { Text("${it.siteId}: ${it.reason.replace('_', ' ')}", style = MaterialTheme.typography.bodySmall) }
    }
    if (timeFor != null && doc != null) {
        val devotional = timeFor == "devotional"
        val schedule = if (devotional) doc.devotionalSchedule else doc.schedule
        val time = rememberTimePickerState(schedule.readyBy.substringBefore(':').toInt(), schedule.readyBy.substringAfter(':').toInt(), false)
        Dialog({ timeFor = null }) { Surface(shape = MaterialTheme.shapes.extraLarge) { Column(Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Ready by"); TimePicker(time)
            Row { TextButton({ timeFor = null }) { Text("Cancel") }; TextButton({
                val next = schedule.copy(enabled = true, readyBy = String.format(Locale.ROOT, "%02d:%02d", time.hour, time.minute))
                vm.edit { if (devotional) it.copy(devotionalSchedule = next) else it.copy(schedule = next) }; timeFor = null
            }) { Text("Set time") } }
        } } }
    }
    if (add != null) ModalBottomSheet(onDismissRequest = { add = null; editing = null }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        if (add == "pick") Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Add a subscription", style = MaterialTheme.typography.headlineSmall)
            listOf("website" to "Website", "devotional" to "Devotional site", "topic" to "Topic").forEach { (kind, label) ->
                Card(onClick = { add = kind }, modifier = Modifier.fillMaxWidth()) { Text(label, Modifier.padding(20.dp), style = MaterialTheme.typography.titleMedium) }
            }
        } else SubscriptionEditor(add!!, editing, state, vm) { add = null; editing = null }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun SubscriptionCard(name: String, description: String, arrival: String, last: String?, playable: PodcastJob?, checked: Boolean, enabled: Boolean,
                             onToggle: (Boolean) -> Unit, onEdit: () -> Unit, onRemove: () -> Unit, onPlay: (PodcastJob) -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) { Text(name, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium); Switch(checked, onToggle, enabled = enabled)
            Box { TextButton({ menu = true }, enabled = enabled) { Text("⋮") }; DropdownMenu(menu, { menu = false }) {
                DropdownMenuItem({ Text("Edit") }, { menu = false; onEdit() }); DropdownMenuItem({ Text("Remove") }, { menu = false; onRemove() })
            } }
        }
        Text(description, style = MaterialTheme.typography.bodyMedium)
        Text(if (checked) arrival else "Paused", style = MaterialTheme.typography.bodySmall)
        last?.let { Text("Last episode: $it", style = MaterialTheme.typography.bodySmall) }
        playable?.let { TextButton({ onPlay(it) }) { Text("Play") } }
    } }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ScheduleCard(title: String, schedule: SubscriptionSchedule, enabled: Boolean, time: () -> Unit, change: (SubscriptionSchedule) -> Unit) {
    Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp)) {
        ToggleRow(title, schedule.enabled, enabled) { change(schedule.copy(enabled = it)) }
        TextButton(time, enabled = enabled, modifier = Modifier.testTag(title)) { Text("Ready by ${java.time.LocalTime.parse(schedule.readyBy).format(DateTimeFormatter.ofPattern("h:mm a", Locale.US))}") }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) { subscriptionDays.forEach { day ->
            FilterChip(day in schedule.days, { change(schedule.copy(days = subscriptionDays.filter { if (it == day) it !in schedule.days else it in schedule.days })) }, { Text(day.replaceFirstChar { it.titlecase() }) }, enabled = enabled)
        } }
        Text(schedule.timezone, style = MaterialTheme.typography.bodySmall)
    } }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SubscriptionEditor(kind: String, editing: DevotionalRule?, state: PodsubUiState, vm: PodsubViewModel, close: () -> Unit) {
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
private fun ToggleRow(label: String, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) { Text(label, Modifier.weight(1f)); Switch(checked, onChange, enabled = enabled) }
}
