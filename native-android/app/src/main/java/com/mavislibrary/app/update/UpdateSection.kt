package com.mavislibrary.app.update

import android.content.ActivityNotFoundException
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import androidx.compose.ui.Alignment
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.material3.Switch
import androidx.compose.foundation.layout.Row
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mavislibrary.app.BuildConfig

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun UpdateSection(updater: AppUpdater, notices: UpdateNotices? = null) {
    val context = LocalContext.current
    val state by updater.state.collectAsStateWithLifecycle()
    fun open(intent: Intent) = try { context.startActivity(intent) } catch (_: ActivityNotFoundException) { }
    Column(Modifier.testTag("app_updates"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("You have version ${BuildConfig.VERSION_NAME} (build ${BuildConfig.VERSION_CODE}).", style = MaterialTheme.typography.bodyMedium)
        if (notices != null) AutoUpdateRow(notices)
        when (val s = state) {
            UpdateState.Idle -> Text("Mavis checks for new versions when it opens and every 6 hours.", style = MaterialTheme.typography.bodySmall)
            UpdateState.Checking -> Text("Checking for updates…")
            UpdateState.UpToDate -> Text("You're up to date.")
            is UpdateState.Available -> Text("Version ${s.info.versionName} is ready." + s.info.notes.takeIf(String::isNotBlank)?.let { "\n$it" }.orEmpty())
            is UpdateState.Downloading -> {
                LinearProgressIndicator(progress = { if (s.total > 0) (s.done.toFloat() / s.total).coerceIn(0f, 1f) else 0f }, modifier = Modifier.fillMaxWidth())
                Text("Downloading ${s.done / 1_048_576} of ${if (s.total > 0) "${s.total / 1_048_576} MB" else "… MB"}")
            }
            is UpdateState.NeedsPermission -> {
                Text("Android needs your OK once before Mavis can install its own updates. Tap Allow, turn on “Allow from this source”, then come back and tap Update now.")
                Button(onClick = { open(UpdateInstaller.allowIntent(context)) }) { Text("Allow") }
            }
            is UpdateState.Installing -> Text("Confirm the update when Android asks. Mavis restarts when it's done.")
            is UpdateState.Failed -> Text(s.message, color = MaterialTheme.colorScheme.error)
        }
        val ready = when (val s = state) {
            is UpdateState.Available -> s.info
            is UpdateState.NeedsPermission -> s.info
            is UpdateState.Failed -> s.info
            else -> null
        }
        val busy = state is UpdateState.Checking || state is UpdateState.Downloading || state is UpdateState.Installing
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (ready != null) Button(onClick = { updater.install(ready) }, enabled = !busy, modifier = Modifier.testTag("update_now")) { Text("Update now") }
            OutlinedButton(onClick = updater::check, enabled = !busy, modifier = Modifier.testTag("check_updates")) { Text("Check for updates") }
            TextButton(onClick = { open(Intent(Intent.ACTION_VIEW, RELEASES_PAGE.toUri()).addCategory(Intent.CATEGORY_BROWSABLE)) }) { Text("All versions") }
        }
    }
}

/** "Install updates automatically": asks for Android's install permission when first turned on. */
@Composable
private fun AutoUpdateRow(notices: UpdateNotices) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val on by notices.autoInstall.collectAsStateWithLifecycle(false)
    // Re-read the permission every time Settings comes back to the front (the user returns from Android's page).
    var allowed by remember { mutableStateOf(UpdateInstaller.canInstall(context)) }
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current
    androidx.compose.runtime.DisposableEffect(lifecycle) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, e ->
            if (e == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                val now = UpdateInstaller.canInstall(context)
                // Coming back from Android's permission page with it granted: install now, not at the next 6-hour check.
                if (now && !allowed) scope.launch { if (notices.autoInstall.first()) UpdateScheduler.checkSoon(context) }
                allowed = now
            }
        }
        lifecycle.lifecycle.addObserver(observer)
        onDispose { lifecycle.lifecycle.removeObserver(observer) }
    }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("Install updates automatically", style = MaterialTheme.typography.bodyLarge)
            Text(when {
                !on -> "Off: Mavis tells you when an update is ready."
                !allowed -> "Needs Android's permission. Tap Allow, turn on “Allow from this source”, then come back."
                else -> "On: new versions download and install by themselves. Android may ask you to tap once the first time."
            }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = on, onCheckedChange = { want ->
            scope.launch { notices.setAutoInstall(want) }
            if (want) {
                if (!UpdateInstaller.canInstall(context)) try { context.startActivity(UpdateInstaller.allowIntent(context)) } catch (_: ActivityNotFoundException) { }
                else UpdateScheduler.checkSoon(context)
            }
        }, modifier = Modifier.testTag("auto_update_switch"))
    }
    if (on && !allowed) TextButton(onClick = { try { context.startActivity(UpdateInstaller.allowIntent(context)) } catch (_: ActivityNotFoundException) { } }) { Text("Allow") }
}
