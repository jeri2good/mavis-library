package com.mavislibrary.app.ui.settings

import android.content.ActivityNotFoundException
import android.content.Intent
import android.app.TimePickerDialog
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import com.mavislibrary.app.data.prefs.ReminderSettings
import com.mavislibrary.app.work.ReminderScheduler
import androidx.core.net.toUri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Policy
import androidx.compose.material.icons.outlined.Headphones
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.DirectionsCar
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Switch
import androidx.compose.material3.FilterChip
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mavislibrary.app.data.kids.kidsPrefs
import com.mavislibrary.app.ui.kids.KidsSetup
import com.mavislibrary.app.AppContainer
import com.mavislibrary.app.BuildConfig
import com.mavislibrary.app.data.secure.Secret
import com.mavislibrary.app.ui.theme.Look
import com.mavislibrary.app.ui.theme.LookChoice
import com.mavislibrary.app.ui.theme.MavisTheme
import kotlinx.coroutines.launch

const val PRIVACY_URL = "https://mavis-library.netlify.app/privacy"

@Composable
fun SettingsScreen(container: AppContainer, onOpenShowcase: () -> Unit, onDriveModeChange: (Boolean) -> Unit = {}, onEnableReminder: () -> Unit = {}, onOpenStats: () -> Unit = {}, onAppLockChange: (Boolean) -> Unit = {}, voice: com.mavislibrary.app.voice.VoiceUi? = null) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val choice by container.settings.lookChoice.collectAsStateWithLifecycle(initialValue = LookChoice.Automatic)
    val matchRoomLight by container.settings.matchRoomLight.collectAsStateWithLifecycle(initialValue = false)
    val appLockEnabled by container.settings.appLockEnabled.collectAsStateWithLifecycle(initialValue = false)
    val lightAvailable = remember(context.applicationContext) { com.mavislibrary.app.ui.theme.LightObserver(context.applicationContext).available }
    val hasCode by container.secureStore.has(Secret.OWNER_ACCESS_CODE).collectAsStateWithLifecycle(initialValue = false)
    val playback by container.settings.playback.collectAsStateWithLifecycle(initialValue = com.mavislibrary.app.data.prefs.PlaybackSettings())
    val reminder by container.settings.reminder.collectAsStateWithLifecycle(initialValue = ReminderSettings())

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp).testTag("settings"),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Settings", style = MaterialTheme.typography.headlineMedium)
        TextButton(onClick = onOpenStats, modifier = Modifier.testTag("open_stats")) { Text("Reading stats") }

        Section("Kids mode", Icons.Outlined.Lock) {
            KidsSetup(remember(context.applicationContext) { context.kidsPrefs() }, onStart = { onDriveModeChange(false) })
        }

        Section("Look", Icons.Outlined.Palette) {
            LookOption(
                title = "Automatic",
                blurb = "Paper by day, Midnight in the evening (7 pm to 7 am).",
                selected = choice == LookChoice.Automatic,
                preview = null,
                onSelect = { scope.launch { container.settings.setLook(LookChoice.Automatic) } },
            )
            Look.selectable.forEach { look ->
                LookOption(
                    title = look.label,
                    blurb = look.blurb,
                    selected = choice == LookChoice.Fixed(look),
                    preview = look,
                    onSelect = { scope.launch { container.settings.setLook(LookChoice.Fixed(look)) } },
                )
            }
            SettingSwitch("Match the room light", "Off by default. Uses the light sensor while the app is open: your Look in daylight, Midnight in dim light. Doesn't change screen brightness.", matchRoomLight) {
                scope.launch { container.settings.setMatchRoomLight(it) }
            }
            if (!lightAvailable) Text("This device has no light sensor; your chosen Look still applies.", style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = onOpenShowcase, modifier = Modifier.testTag("open_showcase")) { Text("See all themes, including Kids") }
        }

        Section("App lock", Icons.Outlined.Lock) {
            SettingSwitch("Lock Mavis with fingerprint / face / device PIN", "Off by default. Locks on launch and after more than 60 seconds away. Kids mode keeps its own PIN gate.", appLockEnabled, onAppLockChange)
        }

        Section("Owner access code", Icons.Outlined.Lock) {
            OwnerCodeEditor(
                hasCode = hasCode,
                onSave = { code, done -> scope.launch { done(container.secureStore.write(Secret.OWNER_ACCESS_CODE, code)) } },
                onRemove = { scope.launch { container.secureStore.clear(Secret.OWNER_ACCESS_CODE) } },
            )
        }

        com.mavislibrary.app.ui.lounge.PodcastSettingsSection(container)

        Section("Listening", Icons.Outlined.Headphones) {
            voice?.let { com.mavislibrary.app.voice.VoiceSettings(it) }
            Text("Default sleep timer", style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(15, 30, 45, 60).forEach { minutes ->
                    FilterChip(
                        selected = playback.defaultSleepMinutes == minutes,
                        onClick = { scope.launch { container.settings.setDefaultSleep(minutes) } },
                        label = { Text(if (minutes == 60) "1 hr" else "$minutes min") },
                    )
                }
            }
            Text("Default speed", style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(.75f, 1f, 1.25f, 1.5f, 2f).forEach { speed ->
                    FilterChip(
                        selected = playback.defaultSpeed == speed,
                        onClick = { scope.launch { container.settings.setDefaultSpeed(speed) } },
                        label = { Text("${speed}×") },
                    )
                }
            }
        }

        Section("Drive mode", Icons.Outlined.DirectionsCar) {
            SettingSwitch(
                "Start for car Bluetooth",
                "Off by default. Permission is requested only when you turn this on.",
                playback.driveModeEnabled,
            ) {
                scope.launch { container.settings.setDriveMode(it) }
                onDriveModeChange(it)
            }
        }

        Section("Reading reminder", Icons.Outlined.Notifications) {
            SettingSwitch("Daily reading reminder", "Off by default. An inexact, device-only reminder; Android may deliver it later than your chosen time. No location or tracking.", reminder.enabled) { enabled ->
                if (enabled) onEnableReminder()
                else scope.launch { container.settings.setReminderEnabled(false); ReminderScheduler.sync(context) }
            }
            OutlinedButton(onClick = {
                TimePickerDialog(context, { _, hour, minute ->
                    scope.launch {
                        container.settings.setReminderTime(LocalTime.of(hour, minute))
                        ReminderScheduler.sync(context)
                    }
                }, reminder.time.hour, reminder.time.minute, true).show()
            }) { Text("Time: ${reminder.time.format(DateTimeFormatter.ofPattern("HH:mm"))}") }
            Text("Notifications are skipped in Kids mode. Adult reading activity isn't logged yet, so reading today won't cancel a reminder.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        Section("Reader buttons", Icons.AutoMirrored.Outlined.MenuBook) {
            SettingSwitch("Volume keys turn pages", "Volume down goes forward; volume up goes back.", playback.volumePageTurns) {
                scope.launch { container.settings.setVolumePageTurns(it) }
            }
            SettingSwitch("Headset next/previous", "Use media next and previous while the reader is open.", playback.headsetPageTurns) {
                scope.launch { container.settings.setHeadsetPageTurns(it) }
            }
            SettingSwitch("Haptic page tick", "A short tactile tick after a native page turn.", playback.hapticPageTurns) {
                scope.launch { container.settings.setHapticPageTurns(it) }
            }
        }

        Section("About", Icons.Outlined.Policy) {
            Text("Mavis Library", style = MaterialTheme.typography.titleMedium)
            Text("Version ${BuildConfig.VERSION_NAME} (build ${BuildConfig.VERSION_CODE}, ${BuildConfig.GIT_SHA}, ${BuildConfig.BUILD_TYPE})",
                style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("about_version"))
            Text("Shelf and Bible use the bundled web screens until their native versions ship.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedButton(onClick = {
                try {
                    context.startActivity(Intent(Intent.ACTION_VIEW, PRIVACY_URL.toUri()).addCategory(Intent.CATEGORY_BROWSABLE))
                } catch (_: ActivityNotFoundException) { }
            }) { Text("Privacy policy") }
        }
    }
}

@Composable
private fun SettingSwitch(title: String, blurb: String, checked: Boolean, onChecked: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(blurb, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = onChecked)
    }
}

@Composable
private fun Section(title: String, icon: androidx.compose.ui.graphics.vector.ImageVector, content: @Composable () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(10.dp))
                Text(title, style = MaterialTheme.typography.titleLarge)
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            content()
        }
    }
}

@Composable
private fun LookOption(title: String, blurb: String, selected: Boolean, preview: Look?, onSelect: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .selectable(selected = selected, onClick = onSelect, role = Role.RadioButton)
            .padding(vertical = 4.dp)
            .testTag("look_${title.lowercase()}"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(blurb, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (preview != null) {
            Spacer(Modifier.width(12.dp))
            MavisTheme(preview) { LookSwatch() }
        }
    }
}

/** A tiny sample of a look, rendered in that look's own theme. */
@Composable
fun LookSwatch(modifier: Modifier = Modifier) {
    Card(
        modifier = modifier.size(width = 72.dp, height = 56.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.background),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        shape = MaterialTheme.shapes.medium,
    ) {
        Column(Modifier.fillMaxSize().padding(6.dp), verticalArrangement = Arrangement.SpaceBetween) {
            Text("Aa", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onBackground)
            Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                listOf(MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.secondary, MaterialTheme.colorScheme.tertiary).forEach {
                    androidx.compose.foundation.Canvas(Modifier.size(10.dp)) { drawCircle(it) }
                }
            }
        }
    }
}

@Composable
private fun OwnerCodeEditor(hasCode: Boolean, onSave: (String, (Boolean) -> Unit) -> Unit, onRemove: () -> Unit) {
    var code by rememberSaveable { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }
    Text(
        if (hasCode) "An access code is saved on this device, encrypted by Android Keystore."
        else "Not set. The owner can type the code to unlock the paid features. It is never built into the app.",
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.testTag("owner_code_status"),
    )
    OutlinedTextField(
        value = code,
        onValueChange = { code = it.take(200); message = null },
        label = { Text(if (hasCode) "Replace access code" else "Access code") },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
        modifier = Modifier.fillMaxWidth().testTag("owner_code_field"),
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(enabled = code.isNotBlank(), onClick = {
            onSave(code.trim()) { ok ->
                message = if (ok) "Saved securely." else "This device refused to store it securely. Nothing was saved."
                if (ok) code = ""
            }
        }) { Text("Save") }
        if (hasCode) OutlinedButton(onClick = { onRemove(); message = "Removed from this device." }) { Text("Remove") }
    }
    message?.let { Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary) }
}
