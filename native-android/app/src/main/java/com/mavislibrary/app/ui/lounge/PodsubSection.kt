package com.mavislibrary.app.ui.lounge

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.mavislibrary.app.data.podcast.PodcastJob
import com.mavislibrary.app.data.podsub.*
import com.mavislibrary.app.ui.podcast.episodeHeadline
import com.mavislibrary.app.ui.podcast.episodeSubtitle
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun PodsubSection(state: PodsubUiState, vm: PodsubViewModel, onDetails: (PodcastJob) -> Unit = {}, onPlay: (List<PodcastJob>) -> Unit) {
    var showTime by remember { mutableStateOf(false) }
    var expanded by remember { mutableStateOf(false) }
    val doc = state.document
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(state.meter, style = MaterialTheme.typography.titleMedium)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Today", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
            TextButton(onClick = { vm.refresh() }, enabled = !state.busy) { Text("Refresh") }
        }
        val ready = state.episodes.filter { it.status == "ready" }
        Button(onClick = { onPlay(ready) }, enabled = ready.isNotEmpty()) { Text("Play all (${ready.size})") }
        if (state.episodes.isEmpty()) Text("No episodes today.")
        state.episodes.forEach { job ->
            Row(Modifier.fillMaxWidth().clickable { onDetails(job) }, verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(episodeHeadline(job), style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(if (job.status == "ready") episodeSubtitle(job).ifEmpty { stageLabel(job) } else stageLabel(job), style = MaterialTheme.typography.bodySmall)
                }
                if (job.status == "ready") TextButton(onClick = { onPlay(listOf(job)) }) { Text("Play") }
            }
        }
        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "Hide subscription settings" else "Subscription settings") }
        if (expanded && doc != null) {
            Text("Sites · highest priority first", style = MaterialTheme.typography.titleMedium)
            if (doc.sites.isEmpty()) Text("Follow sites in the Sites tab to add them here.")
            doc.sites.sortedBy { it.priority }.forEachIndexed { index, site ->
                fun change(update: (SiteRule) -> SiteRule) = vm.edit { d -> d.copy(sites = d.sites.map { if (it.id == site.id) update(it) else it }) }
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Text(site.name, style = MaterialTheme.typography.titleMedium)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            listOf(0, 1, 2, 3, 5).forEach { count ->
                                FilterChip(selected = if (count == 0) site.mode == "off" else site.mode == "top" && site.count == count,
                                    onClick = { change { it.copy(mode = if (count == 0) "off" else "top", count = count.takeIf { it > 0 }) } },
                                    enabled = !state.busy, label = { Text(if (count == 0) "Off" else "Top $count") })
                            }
                        }
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            listOf("briefing" to "Briefing", "separate" to "Separate").forEach { (value, label) ->
                                FilterChip(site.delivery == value, { change { it.copy(delivery = value) } }, { Text(label) }, enabled = !state.busy)
                            }
                        }
                        Row(Modifier.fillMaxWidth()) {
                            TextButton(onClick = { vm.move(site.id, -1) }, enabled = index > 0 && !state.busy, modifier = Modifier.weight(1f)) { Text("Move up") }
                            TextButton(onClick = { vm.move(site.id, 1) }, enabled = index < doc.sites.lastIndex && !state.busy, modifier = Modifier.weight(1f)) { Text("Move down") }
                        }
                    }
                }
            }
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Schedule", style = MaterialTheme.typography.titleMedium)
                    ToggleRow("Scheduled podcasts", doc.schedule.enabled, !state.busy) { on ->
                        if (on) showTime = true else vm.edit { it.copy(schedule = it.schedule.copy(enabled = false)) }
                    }
                    OutlinedButton(onClick = { showTime = true }, enabled = !state.busy) { Text("Ready by ${doc.schedule.readyBy}") }
                    Text("Timezone: ${doc.schedule.timezone}", style = MaterialTheme.typography.bodySmall)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        subscriptionDays.forEach { day ->
                            FilterChip(day in doc.schedule.days, { vm.edit { d -> d.copy(schedule = d.schedule.copy(days =
                                subscriptionDays.filter { if (it == day) it !in d.schedule.days else it in d.schedule.days })) } },
                                { Text(day.replaceFirstChar { it.titlecase() }) }, enabled = !state.busy)
                        }
                    }
                    ToggleRow(if (doc.engine == "notebooklm") "NotebookLM only" else "NotebookLM, backup voices if it fails",
                        doc.engine == "auto", !state.busy) { backup -> vm.edit { it.copy(engine = if (backup) "auto" else "notebooklm") } }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf("newest" to "Newest", "important" to "Most important").forEach { (rank, label) ->
                            FilterChip(doc.rank == rank, { vm.edit { it.copy(rank = rank) } }, { Text(label) }, enabled = !state.busy)
                        }
                    }
                    Text("Keep episodes ${doc.keepDays} days")
                    Slider(doc.keepDays.toFloat(), { value -> vm.edit { it.copy(keepDays = value.toInt()) } },
                        valueRange = 1f..30f, steps = 28, enabled = !state.busy)
                    ToggleRow("Telegram notifications", doc.notify.telegram, !state.busy) { enabled ->
                        vm.edit { it.copy(notify = SubscriptionNotify(enabled)) }
                    }
                }
            }
            Button(onClick = { vm.save() }, enabled = state.dirty && !state.busy, modifier = Modifier.fillMaxWidth()) { Text("Save subscription") }
        }
        Button(onClick = { vm.runNow() }, enabled = doc != null && !state.busy && !state.dirty) { Text("Run now") }
        if (state.dirty) Text("Save changes to apply them before running.", style = MaterialTheme.typography.bodySmall)
        state.nextRunAt?.let { Text("Next start: $it", style = MaterialTheme.typography.bodySmall) }
        state.lastRun?.let { run ->
            Text("Last run: ${run.status} · ${run.episodes.size} episode(s)")
            run.skipped.forEach { skipped ->
                Text("${doc?.sites?.find { it.id == skipped.siteId }?.name ?: skipped.siteId}: ${skipped.reason}", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
    if (showTime && doc != null) {
        val time = rememberTimePickerState(initialHour = doc.schedule.readyBy.substringBefore(':').toInt(),
            initialMinute = doc.schedule.readyBy.substringAfter(':').toInt(), is24Hour = true)
        Dialog(onDismissRequest = { showTime = false }) {
            Surface(shape = MaterialTheme.shapes.extraLarge) {
                Column(Modifier.padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Ready by", style = MaterialTheme.typography.titleLarge)
                    TimePicker(time)
                    Row {
                        TextButton(onClick = { showTime = false }) { Text("Cancel") }
                        TextButton(onClick = {
                            vm.edit { it.copy(schedule = it.schedule.copy(enabled = true,
                                readyBy = String.format(Locale.ROOT, "%02d:%02d", time.hour, time.minute))) }
                            showTime = false
                        }) { Text("Set time") }
                    }
                }
            }
        }
    }
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, Modifier.weight(1f)); Switch(checked, onChange, enabled = enabled)
    }
}
