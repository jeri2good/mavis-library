package com.mavislibrary.app.ui.lounge

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.initializer
import com.mavislibrary.app.AppContainer
import com.mavislibrary.app.data.kids.kidsPrefs
import com.mavislibrary.app.data.podcast.loungeRouteAllowed
import com.mavislibrary.app.data.podsub.DevotionalRule
import com.mavislibrary.app.data.podsub.SubscriptionDocument
import com.mavislibrary.app.data.podsub.subscriptionDays
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale
import java.util.UUID

/** Topic chips on screen D (approved list). Free text is the "＋ Custom" chip. */
val devotionalTopics = listOf("Anxiety", "Marriage", "Fatherhood", "Faith", "Grief", "Gratitude")
val DEFAULT_DEVOTIONAL_TIME: LocalTime = LocalTime.of(19, 0)

/** Shared devotional time: the running schedule's when devotionals exist, else 7:00 PM. */
fun defaultDevotionalTime(doc: SubscriptionDocument?): LocalTime =
    doc?.takeIf { it.devotionals.isNotEmpty() }?.let { runCatching { LocalTime.parse(it.devotionalSchedule.readyBy) }.getOrNull() } ?: DEFAULT_DEVOTIONAL_TIME

/** "First one: tonight 7:00 PM": the next delivery day (today if the time hasn't passed) on [days]. */
fun firstDeliveryLabel(time: LocalTime, days: List<String>, now: ZonedDateTime): String {
    val clock = time.format(DateTimeFormatter.ofPattern("h:mm a", Locale.US))
    for (offset in 0..7) {
        val date = now.toLocalDate().plusDays(offset.toLong())
        if (days.isNotEmpty() && subscriptionDays[date.dayOfWeek.value - 1] !in days) continue
        if (offset == 0 && !time.isAfter(now.toLocalTime())) continue
        val day = when (offset) {
            0 -> if (time.hour >= 17) "tonight" else "today"
            1 -> "tomorrow"
            else -> date.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.US)
        }
        return "First one: $day $clock"
    }
    return "First one: $clock"
}

fun daysLabel(days: List<String>) = if (days.isEmpty() || days.size == 7) "every day" else days.joinToString(", ") { it.replaceFirstChar(Char::titlecase) }

/** Podcast subscriptions view model for this listener, or null when nobody is signed in (no requests are made). */
@Composable
fun rememberPodsubViewModel(container: AppContainer): PodsubViewModel? {
    val signedIn by container.accounts.signedIn.collectAsStateWithLifecycle(initialValue = null)
    val owner by container.secureStore.has(com.mavislibrary.app.data.secure.Secret.OWNER_ACCESS_CODE).collectAsStateWithLifecycle(initialValue = null)
    val accountId by container.secureStore.observe(com.mavislibrary.app.data.secure.Secret.ACCOUNT_USER_ID).collectAsStateWithLifecycle(initialValue = null)
    if (signedIn != true && owner != true) return null
    val identity = if (owner == true) "owner" else accountId
    val vm: PodsubViewModel = androidx.lifecycle.viewmodel.compose.viewModel(key = "podsub-$identity",
        factory = androidx.lifecycle.viewmodel.viewModelFactory { initializer { PodsubViewModel(container.podsubRepository) } })
    LaunchedEffect(vm) { vm.refresh() }
    return vm
}

/** Screen D: start a daily devotional. [mode] is [DAILY_SITE] or [DAILY_TOPIC]. */
@Composable
fun DailyDevotionalScreen(container: AppContainer, mode: String, onBack: () -> Unit, onSignIn: () -> Unit) {
    val context = LocalContext.current
    val kids by remember { context.kidsPrefs() }.state.collectAsStateWithLifecycle(initialValue = null)
    if (!loungeRouteAllowed(kids?.enabled)) return
    val vm = rememberPodsubViewModel(container)
    if (vm == null) {
        Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            BackRow(onBack)
            Text("Start a daily devotional", style = MaterialTheme.typography.headlineLarge)
            Text("Sign in to make and hear podcasts")
            Button(onClick = onSignIn) { Text("Sign in in Settings") }
        }
        return
    }
    val state by vm.state.collectAsStateWithLifecycle()
    DailyDevotionalContent(state, topicFirst = mode == DAILY_TOPIC, onPreview = { vm.preview(it, true) }, onBack = onBack,
        onStart = { rule, time, saved -> vm.startDevotional(rule, String.format(Locale.ROOT, "%02d:%02d", time.hour, time.minute), ZoneId.systemDefault().id, saved) })
}

@Composable
private fun BackRow(onBack: () -> Unit) {
    IconButton(onClick = onBack, modifier = Modifier.size(48.dp)) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back") }
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun DailyDevotionalContent(
    state: PodsubUiState,
    topicFirst: Boolean,
    onPreview: (String) -> Unit,
    onStart: (DevotionalRule, LocalTime, () -> Unit) -> Unit,
    onBack: () -> Unit,
    now: () -> ZonedDateTime = { ZonedDateTime.now() },
) {
    val doc = state.document
    // Exactly one of: a site ("today" / "other"), a chip topic, or a custom topic.
    var site by rememberSaveable { mutableStateOf(if (topicFirst) null else "today") }
    var topic by rememberSaveable { mutableStateOf<String?>(null) }
    var custom by rememberSaveable { mutableStateOf(false) }
    var customText by rememberSaveable { mutableStateOf("") }
    var otherUrl by rememberSaveable { mutableStateOf("") }
    var otherName by rememberSaveable { mutableStateOf("") }
    var chosenTime by rememberSaveable { mutableStateOf<String?>(null) }
    var picking by remember { mutableStateOf(false) }
    var confirmation by rememberSaveable { mutableStateOf<String?>(null) }
    var requested by remember { mutableStateOf<String?>(null) }
    val time = chosenTime?.let(LocalTime::parse) ?: defaultDevotionalTime(doc)
    val siteUrl = when (site) { "today" -> TODAY_DEVOTIONAL_URL; "other" -> otherUrl.trim(); else -> null }
    val topicValue = (if (custom) customText else topic.orEmpty()).trim()
    // Preview the preset as soon as the screen can (the view model drops calls while it's busy loading).
    LaunchedEffect(site, state.busy, doc != null) {
        if (site == "today" && doc != null && !state.busy && requested != TODAY_DEVOTIONAL_URL) { requested = TODAY_DEVOTIONAL_URL; onPreview(TODAY_DEVOTIONAL_URL) }
    }
    val preview = state.devotionalPreview?.takeIf { siteUrl != null && state.previewUrl == siteUrl }
    val canStart = doc != null && !state.busy && if (siteUrl != null) preview != null else topicValue.isNotBlank()

    confirmation?.let { first ->
        Column(Modifier.fillMaxSize().padding(24.dp).testTag("daily_confirmation"), verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Outlined.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(64.dp))
            Text("You're all set", style = MaterialTheme.typography.headlineMedium)
            Text("A new Bible lesson arrives at ${readyByLabel(time.toString())}, ${daysLabel(doc?.devotionalSchedule?.days.orEmpty())}.",
                style = MaterialTheme.typography.bodyLarge)
            Text(first, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary)
            Text("Change it any time in Settings → Podcasts.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Button(onClick = onBack, modifier = Modifier.fillMaxWidth().height(56.dp)) { Text("Done") }
        }
        return
    }

    Column(Modifier.fillMaxSize().testTag("daily_devotional")) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            BackRow(onBack)
            Text("Start a daily devotional", Modifier.semantics { heading() }, style = MaterialTheme.typography.headlineLarge)
            Text("A new Bible lesson, every day.", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)

            Text("From a site", style = MaterialTheme.typography.titleMedium)
            SourceOption(TODAY_DEVOTIONAL_LABEL, "todaydevotional.com", site == "today", "source_today") { site = "today"; topic = null; custom = false }
            SourceOption("Another site", null, site == "other", "source_other") { site = "other"; topic = null; custom = false }
            if (site == "other") {
                OutlinedTextField(otherUrl, { otherUrl = it.take(2048) }, label = { Text("Devotional site link") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(otherName, { otherName = it.take(200) }, label = { Text("Name (optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedButton(onClick = { requested = otherUrl.trim(); onPreview(otherUrl.trim()) }, enabled = otherUrl.isNotBlank() && !state.busy,
                    modifier = Modifier.heightIn(min = 48.dp)) { Text("Preview") }
            }

            Text("Or a topic", style = MaterialTheme.typography.titleMedium)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                devotionalTopics.forEach { name ->
                    TopicChip(name, selected = !custom && topic == name) { topic = name; custom = false; site = null }
                }
                TopicChip("＋ Custom", selected = custom, tag = "topic_chip_custom") { custom = true; topic = null; site = null }
            }
            if (custom) OutlinedTextField(customText, { customText = it.take(120) }, label = { Text("Your topic") }, singleLine = true, modifier = Modifier.fillMaxWidth())

            Text("When", style = MaterialTheme.typography.titleMedium)
            Surface(onClick = { picking = true }, shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainer,
                modifier = Modifier.fillMaxWidth().testTag("daily_time")) {
                Row(Modifier.heightIn(min = 56.dp).padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.Schedule, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(14.dp))
                    Text(readyByLabel(time.toString()), style = MaterialTheme.typography.titleLarge)
                    Spacer(Modifier.width(6.dp))
                    Text(daysLabel(doc?.devotionalSchedule?.days.orEmpty()), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("Change", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                }
            }
            if (doc?.devotionals?.isNotEmpty() == true) Text("All your devotionals share this time.", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)

            if (siteUrl != null) {
                Text("Today's devotion", style = MaterialTheme.typography.titleMedium)
                when {
                    preview != null -> PreviewCard(preview.title, preview.reference)
                    state.busy -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        CircularProgressIndicator(Modifier.size(24.dp)); Text("Loading today's devotion…")
                    }
                    site == "other" && requested != siteUrl -> Text("Tap Preview to check the site.", style = MaterialTheme.typography.bodyMedium)
                    else -> TextButton(onClick = { requested = siteUrl; onPreview(siteUrl) }, enabled = doc != null) { Text("Couldn't load it. Try again") }
                }
            } else if (topicValue.isNotBlank()) {
                Text("Each day", style = MaterialTheme.typography.titleMedium)
                // Topics have no preview: the passage is chosen when each episode is made.
                PreviewCard("A daily Bible lesson on $topicValue", null)
            }
            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            if (doc == null && state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            Spacer(Modifier.height(8.dp))
        }
        Button(
            onClick = {
                val rule = if (siteUrl != null) DevotionalRule(UUID.randomUUID().toString(), "site",
                    if (site == "today") TODAY_DEVOTIONAL_LABEL else otherName.trim().ifBlank { preview?.title ?: "Devotional" }, url = siteUrl)
                else DevotionalRule(UUID.randomUUID().toString(), "topic", topicValue, topic = topicValue)
                onStart(rule, time) { confirmation = firstDeliveryLabel(time, doc?.devotionalSchedule?.days.orEmpty(), now()) }
            },
            enabled = canStart,
            modifier = Modifier.fillMaxWidth().padding(16.dp).height(56.dp).testTag("daily_start"),
        ) { Text(if (state.busy && doc != null) "Working…" else "Start", style = MaterialTheme.typography.titleMedium) }
    }

    if (picking) {
        val picker = rememberTimePickerState(time.hour, time.minute, false)
        Dialog({ picking = false }) {
            Surface(shape = MaterialTheme.shapes.extraLarge) {
                Column(Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Ready by"); TimePicker(picker)
                    Row {
                        TextButton({ picking = false }) { Text("Cancel") }
                        TextButton({ chosenTime = LocalTime.of(picker.hour, picker.minute).toString(); picking = false }) { Text("Set time") }
                    }
                }
            }
        }
    }
}

@Composable
private fun SourceOption(title: String, subtitle: String?, selected: Boolean, tag: String, onSelect: () -> Unit) {
    Row(Modifier.fillMaxWidth().clip(MaterialTheme.shapes.large)
        .background(if (selected) MaterialTheme.colorScheme.surfaceContainer else androidx.compose.ui.graphics.Color.Transparent)
        .selectable(selected, onClick = onSelect, role = Role.RadioButton).heightIn(min = 56.dp).padding(horizontal = 12.dp, vertical = 8.dp).testTag(tag),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        RadioButton(selected = selected, onClick = null)
        Column {
            Text(title, style = MaterialTheme.typography.titleMedium)
            subtitle?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}

/** A topic chip; selected is filled with the primary colour (approved change), not just ticked. */
@Composable
private fun TopicChip(label: String, selected: Boolean, tag: String = "topic_chip_$label", onClick: () -> Unit) {
    FilterChip(
        selected = selected, onClick = onClick, label = { Text(label, style = MaterialTheme.typography.titleSmall) },
        colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = MaterialTheme.colorScheme.primary,
            selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
        ),
        modifier = Modifier.heightIn(min = 48.dp).testTag(tag),
    )
}

@Composable
private fun PreviewCard(title: String, reference: String?) {
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min).clip(MaterialTheme.shapes.medium).background(MaterialTheme.colorScheme.surfaceContainer).testTag("daily_preview")) {
        Box(Modifier.width(4.dp).fillMaxHeight().background(MaterialTheme.colorScheme.primary))
        Column(Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleLarge)
            reference?.let { Text(it, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}
