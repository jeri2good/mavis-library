package com.mavislibrary.app.ui.lounge

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mavislibrary.app.AppContainer
import com.mavislibrary.app.data.kids.kidsPrefs
import com.mavislibrary.app.data.podcast.PodcastPreferences
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

@Composable
fun PodcastSettingsSection(container: AppContainer) {
    val context = LocalContext.current
    val kids by remember { context.kidsPrefs() }.state.collectAsStateWithLifecycle(initialValue = null)
    if (kids?.enabled != false) return
    val prefs by container.podcastSettings.preferences.collectAsStateWithLifecycle(initialValue = PodcastPreferences())
    val style = prefs.style.takeIf { it in com.mavislibrary.app.data.podcast.podcastStyles } ?: "deep-dive"
    var message by remember { mutableStateOf<String?>(null) }
    var days by remember { mutableStateOf<String?>(null) }
    var confirmCleanup by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    fun defaults(s: String, length: String, engine: String) { scope.launch {
        if (context.kidsPrefs().state.first().enabled) return@launch
        try { container.podcastSettings.setDefaults(s, length, engine) }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) { message = "Could not save podcast preferences." }
    } }
    Card(Modifier.fillMaxWidth().testTag("podcast_settings")) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Podcast Lounge", style = MaterialTheme.typography.titleLarge)
        Text("Uses your account through Mavis Library's public gateway. No studio address or key to paste. Approved subscribers and the owner can create podcasts.")
        Text("Default podcast options", style = MaterialTheme.typography.titleMedium)
        PodcastChoices(style, prefs.length, prefs.engine,
            { defaults(it, prefs.length, prefs.engine) },
            { defaults(style, it, prefs.engine) },
            { defaults(style, prefs.length, it) })
        Text("Downloaded podcasts stay on this device. Previous direct-studio jobs are not migrated to the gateway.", style = MaterialTheme.typography.bodySmall)
        OutlinedTextField(days ?: prefs.cleanupDays.toString(), { days = it.filter(Char::isDigit).take(4) }, label = { Text("Delete finished podcasts older than N days") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true, modifier = Modifier.fillMaxWidth())
        Text("Local cleanup only: removes old finished library entries and downloaded audio on Lounge entry. Studio originals are untouched. 0 keeps everything; unknown dates are kept.", style = MaterialTheme.typography.bodySmall)
        OutlinedButton(enabled = !busy, onClick = { confirmCleanup = true }) { Text("Apply local cleanup") }
        message?.let { Text(it) }
    } }
    if (confirmCleanup) AlertDialog(onDismissRequest = { confirmCleanup = false }, title = { Text("Apply local cleanup?") }, text = { Text("Finished podcasts older than ${days ?: prefs.cleanupDays} days will be removed from this device now and on future Lounge visits. 0 disables cleanup. Originals at your studio are kept.") }, confirmButton = { TextButton(onClick = {
        confirmCleanup = false
        scope.launch {
            if (busy || context.kidsPrefs().state.first().enabled) return@launch
            busy = true
            try {
                val n = (days ?: prefs.cleanupDays.toString()).toIntOrNull() ?: error("Enter days from 0 to 3650.")
                container.podcastSettings.setCleanupDays(n)
                val removed = container.audioFiles.cleanupPodcasts(container.podcastSettings, n)
                days = null; message = "$removed finished podcasts removed locally."
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { message = "Could not apply local cleanup. Enter days from 0 to 3650 and try again." }
            finally { busy = false }
        }
    }) { Text("Apply") } }, dismissButton = { TextButton(onClick = { confirmCleanup = false }) { Text("Cancel") } })
}
