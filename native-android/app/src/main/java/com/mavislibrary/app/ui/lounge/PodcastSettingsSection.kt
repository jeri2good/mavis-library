package com.mavislibrary.app.ui.lounge

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mavislibrary.app.AppContainer
import com.mavislibrary.app.data.podcast.PodcastPreferences
import com.mavislibrary.app.data.secure.Secret
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
fun PodcastSettingsSection(container: AppContainer) {
    val prefs by container.podcastSettings.preferences.collectAsStateWithLifecycle(initialValue = PodcastPreferences())
    val hasKey by container.secureStore.has(Secret.PODCAST_STUDIO_TOKEN).collectAsStateWithLifecycle(initialValue = false)
    var address by remember { mutableStateOf<String?>(null) }
    var key by remember { mutableStateOf("") }
    var days by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var confirmCleanup by remember { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    fun act(action: suspend () -> Unit) { scope.launch {
        if (busy) return@launch
        busy = true; message = null
        try { action() } catch (e: CancellationException) { throw e } catch (e: Exception) { message = e.message ?: "Studio unavailable." }
        finally { busy = false }
    } }
    Card(Modifier.fillMaxWidth().testTag("podcast_settings")) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Podcast studio", style = MaterialTheme.typography.titleLarge)
        Text("Podcasts are made by your Mavis studio at home. Use the iMac's LAN or Tailscale address, including its port. The address starts blank.")
        OutlinedTextField(address ?: prefs.address, { address = it.take(4096) }, label = { Text("Podcast studio address") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Text("HTTPS is supported. Private HTTP is restricted to the exact IP you enter (LAN or Tailscale); HTTP is not encrypted. Changing the address clears the old key.", style = MaterialTheme.typography.bodySmall)
        Text(if (hasKey) "A key is stored encrypted using Android Keystore." else "No studio key saved.", style = MaterialTheme.typography.bodySmall)
        OutlinedTextField(key, { key = it.take(4096) }, label = { Text("Studio Bearer key") }, singleLine = true, visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false), modifier = Modifier.fillMaxWidth())
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = { key = clipboard.getText()?.text.orEmpty().trim().take(4096) }) { Text("Paste key") }
            TextButton(enabled = hasKey && !busy, onClick = { act { container.podcastSettings.clearToken(); message = "Key removed." } }) { Text("Remove key") }
        }
        Button(enabled = !busy, onClick = { act {
            val ok = container.podcastSettings.configure(address ?: prefs.address, key)
            if (ok) { key = ""; address = null; message = "Studio settings saved securely." }
            else message = "Android refused to encrypt the key. No key was saved."
        } }) { Text("Save address and key") }
        OutlinedButton(enabled = !busy && prefs.address.isNotBlank(), onClick = { act {
            val health = container.podcastApi(prefs.address).health()
            message = health.label + "\nNotebookLM: ${health.engines.notebooklm.note.ifBlank { if (health.engines.notebooklm.loggedIn) "logged in" else "not logged in" }}\nLocal: ${health.engines.local.note.ifBlank { if (health.engines.local.available) "available" else "unavailable" }}"
        } }) { Text("Test saved connection") }
        Text("Default podcast options", style = MaterialTheme.typography.titleMedium)
        PodcastChoices(prefs.style, prefs.length, prefs.engine,
            { act { container.podcastSettings.setDefaults(it, prefs.length, prefs.engine) } },
            { act { container.podcastSettings.setDefaults(prefs.style, it, prefs.engine) } },
            { act { container.podcastSettings.setDefaults(prefs.style, prefs.length, it) } })
        OutlinedTextField(days ?: prefs.cleanupDays.toString(), { days = it.filter(Char::isDigit).take(4) }, label = { Text("Delete finished podcasts older than N days") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true, modifier = Modifier.fillMaxWidth())
        Text("Local cleanup only: removes old finished library entries and downloaded audio on Lounge entry. Studio originals are untouched. 0 keeps everything; unknown dates are kept.", style = MaterialTheme.typography.bodySmall)
        OutlinedButton(enabled = !busy, onClick = { confirmCleanup = true }) { Text("Apply local cleanup") }
        message?.let { Text(it) }
    } }
    if (confirmCleanup) AlertDialog(onDismissRequest = { confirmCleanup = false }, title = { Text("Apply local cleanup?") }, text = { Text("Finished podcasts older than ${days ?: prefs.cleanupDays} days will be removed from this device now and on future Lounge visits. 0 disables cleanup. Originals at your studio are kept.") }, confirmButton = { TextButton(onClick = { confirmCleanup = false; act {
        val n = (days ?: prefs.cleanupDays.toString()).toIntOrNull() ?: error("Enter days from 0 to 3650.")
        container.podcastSettings.setCleanupDays(n)
        val removed = container.audioFiles.cleanupPodcasts(container.podcastSettings, n)
        days = null; message = "$removed finished podcasts removed locally."
    } }) { Text("Apply") } }, dismissButton = { TextButton(onClick = { confirmCleanup = false }) { Text("Cancel") } })
}
