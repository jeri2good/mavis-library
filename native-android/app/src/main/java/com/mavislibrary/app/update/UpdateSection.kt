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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mavislibrary.app.BuildConfig

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun UpdateSection(updater: AppUpdater) {
    val context = LocalContext.current
    val state by updater.state.collectAsStateWithLifecycle()
    fun open(intent: Intent) = try { context.startActivity(intent) } catch (_: ActivityNotFoundException) { }
    Column(Modifier.testTag("app_updates"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("You have version ${BuildConfig.VERSION_NAME} (build ${BuildConfig.VERSION_CODE}).", style = MaterialTheme.typography.bodyMedium)
        when (val s = state) {
            UpdateState.Idle -> Text("Mavis checks for new versions when it opens and once a day.", style = MaterialTheme.typography.bodySmall)
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
