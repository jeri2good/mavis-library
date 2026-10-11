package com.mavislibrary.app.ui.listen

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Bedtime
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Forward30
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Replay10
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mavislibrary.app.audio.AudioCommands
import com.mavislibrary.app.audio.PlaybackController

@Composable
fun DriveModeScreen(controller: PlaybackController, onClose: () -> Unit) {
    val state by controller.state.collectAsStateWithLifecycle()
    LaunchedEffect(state.connected, state.chapterCount) {
        if (state.connected && state.chapterCount > 0 && !state.playing) controller.toggle()
    }
    Box(Modifier.fillMaxSize().background(Color(0xff080b0e))) {
        IconButton(onClick = onClose, modifier = Modifier.align(Alignment.TopEnd).size(72.dp)) { Icon(Icons.Outlined.Close, "Close drive mode", tint = Color.White) }
        Column(Modifier.fillMaxWidth().align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(30.dp)) {
            Text(state.title.ifBlank { "Ready to listen" }, color = Color.White, style = MaterialTheme.typography.headlineLarge, maxLines = 2)
            Text(state.chapter, color = Color.LightGray, style = MaterialTheme.typography.titleLarge, maxLines = 1)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                FilledIconButton(onClick = { controller.seekBy(-15_000) }, modifier = Modifier.size(92.dp)) { Icon(Icons.Outlined.Replay10, "Back 15", Modifier.size(52.dp)) }
                FilledIconButton(onClick = controller::toggle, modifier = Modifier.size(124.dp)) { Icon(if (state.playing) Icons.Outlined.Pause else Icons.Outlined.PlayArrow, "Play or pause", Modifier.size(72.dp)) }
                FilledIconButton(onClick = { controller.seekBy(30_000) }, modifier = Modifier.size(92.dp)) { Icon(Icons.Outlined.Forward30, "Forward 30", Modifier.size(52.dp)) }
            }
            FilledIconButton(onClick = { controller.command(AudioCommands.SLEEP_30) }, modifier = Modifier.size(86.dp)) { Icon(Icons.Outlined.Bedtime, "Sleep 30 minutes", Modifier.size(46.dp)) }
        }
    }
}
