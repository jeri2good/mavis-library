package com.mavislibrary.app.ui.settings

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.ConnectivityManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.RecordVoiceOver
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mavislibrary.app.AppContainer
import com.mavislibrary.app.readaloud.KokoroModel
import com.mavislibrary.app.readaloud.KokoroState
import com.mavislibrary.app.readaloud.NATURAL_VOICES
import com.mavislibrary.app.readaloud.ReadAloudSettings
import com.mavislibrary.app.readaloud.VoiceEngineKind
import com.mavislibrary.app.readaloud.defaultEngine
import kotlinx.coroutines.launch

/** Only reachable outside Kids mode (the Settings screen is blank there), so Kids mode never sees a download prompt. */
@Composable
fun ReadAloudSettingsSection(container: AppContainer) {
    val readAloud = container.readAloud
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val settings by readAloud.prefs.settings.collectAsStateWithLifecycle(initialValue = ReadAloudSettings())
    val model by readAloud.model.state.collectAsStateWithLifecycle()
    val installed = readAloud.naturalSupported && model == KokoroState.Installed
    val active = defaultEngine(settings, installed)

    Section("Read-aloud voice", Icons.Outlined.RecordVoiceOver) {
        Text("Used by Read in the Bible and the book reader. Everything stays on this phone.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

        if (readAloud.naturalSupported) {
            VoiceOption("Natural (Kokoro)", "A warmer voice that runs on this phone, no internet needed once downloaded.", active == VoiceEngineKind.NATURAL, enabled = installed, tag = "read_aloud_natural") {
                scope.launch { readAloud.prefs.setEngine(VoiceEngineKind.NATURAL) }
            }
            NaturalModelControls(model, onDownload = readAloud::startDownload, onCancel = readAloud::cancelDownload, onRemove = readAloud::removeModel, context = context)
            if (installed) NATURAL_VOICES.forEach { voice ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Row(
                        Modifier.weight(1f)
                            .selectable(selected = settings.naturalVoice == voice.id, role = Role.RadioButton) {
                                scope.launch {
                                    readAloud.prefs.setNaturalVoice(voice.id)
                                    readAloud.prefs.setEngine(VoiceEngineKind.NATURAL)
                                }
                            }
                            .padding(vertical = 4.dp, horizontal = 12.dp)
                            .testTag("read_aloud_voice_${voice.id}"),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = active == VoiceEngineKind.NATURAL && settings.naturalVoice == voice.id, onClick = null)
                        Spacer(Modifier.width(12.dp))
                        Text(voice.label + if (voice.lang == "en-GB") " · British" else " · American", style = MaterialTheme.typography.bodyLarge)
                    }
                    IconButton(onClick = { readAloud.sample(voice.uri) }, modifier = Modifier.testTag("read_aloud_sample_${voice.id}")) {
                        Icon(Icons.Outlined.PlayArrow, contentDescription = "Play a sample of ${voice.label}")
                    }
                }
            }
        }

        VoiceOption("Phone voice", "The speech voice set up in Android.", active == VoiceEngineKind.PHONE, enabled = true, tag = "read_aloud_phone") {
            scope.launch { readAloud.prefs.setEngine(VoiceEngineKind.PHONE) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = { readAloud.sample("android:") }, modifier = Modifier.testTag("read_aloud_sample_phone")) { Text("Play sample") }
            TextButton(onClick = {
                try {
                    context.startActivity(Intent("com.android.settings.TTS_SETTINGS"))
                } catch (_: ActivityNotFoundException) { }
            }, modifier = Modifier.testTag("read_aloud_android_settings")) { Text("Open Android voice settings") }
        }
    }
}

@Composable
private fun VoiceOption(title: String, blurb: String, selected: Boolean, enabled: Boolean, tag: String, onSelect: () -> Unit) {
    Row(
        Modifier.fillMaxWidth()
            .selectable(selected = selected, enabled = enabled, onClick = onSelect, role = Role.RadioButton)
            .padding(vertical = 4.dp)
            .testTag(tag),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null, enabled = enabled)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(blurb, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun NaturalModelControls(model: KokoroState, onDownload: () -> Unit, onCancel: () -> Unit, onRemove: () -> Unit, context: android.content.Context) {
    val sizeMb = remember { KokoroModel().archiveBytes / 1_000_000 }
    val metered = remember(model) { context.getSystemService(ConnectivityManager::class.java)?.isActiveNetworkMetered == true }
    fun label(resumable: Long) = if (resumable > 0) "Resume download (${(KokoroModel().archiveBytes - resumable) / 1_000_000} MB left)" else "Download ($sizeMb MB)"
    when (model) {
        is KokoroState.NotInstalled -> {
            Button(onClick = onDownload, modifier = Modifier.testTag("read_aloud_download")) { Text(label(model.resumableBytes)) }
            Text(if (metered) "You're on mobile data. Wi-Fi is recommended for a download this size." else "Wi-Fi recommended.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        is KokoroState.Downloading -> {
            LinearProgressIndicator(progress = { if (model.total > 0) model.done.toFloat() / model.total else 0f }, modifier = Modifier.fillMaxWidth().testTag("read_aloud_progress"))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${model.done / 1_000_000} of ${model.total / 1_000_000} MB", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                TextButton(onClick = onCancel) { Text("Pause") }
            }
        }
        KokoroState.Verifying, KokoroState.Unpacking -> {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            Text(if (model == KokoroState.Verifying) "Checking the download…" else "Setting up the voice…", style = MaterialTheme.typography.bodySmall)
        }
        KokoroState.Installed -> Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Downloaded ✓", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f).testTag("read_aloud_downloaded"))
            TextButton(onClick = onRemove, modifier = Modifier.testTag("read_aloud_remove")) { Text("Remove") }
        }
        is KokoroState.Failed -> {
            Text(model.message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            Button(onClick = onDownload, modifier = Modifier.testTag("read_aloud_download")) { Text(label(model.resumableBytes)) }
        }
    }
}
