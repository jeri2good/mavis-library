package com.mavislibrary.app.voice

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.SystemClock
import android.provider.Settings
import android.speech.SpeechRecognizer
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.Modifier
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.mavislibrary.app.audio.PlaybackController

class VoiceUi(context: Context) {
    private val prefs = context.getSharedPreferences("voice_commands", Context.MODE_PRIVATE)
    var enabled by mutableStateOf(prefs.getBoolean("enabled", false))
        private set
    var attached = true
    var foreground by mutableStateOf(false)
    var permission by mutableStateOf(false)
    var message by mutableStateOf("")
    var available by mutableStateOf(false)
    lateinit var bus: VoiceCommandBus
    fun changeEnabled(value: Boolean) {
        enabled = value
        prefs.edit().putBoolean("enabled", value).apply()
        if (!value) { bus.cancel(); message = "" }
    }
    fun start() { bus.start() }
}

@Composable
fun rememberVoiceUi(controller: PlaybackController, kidsMode: Boolean, page: (Boolean) -> Boolean): VoiceUi {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val haptic = LocalHapticFeedback.current
    val currentKids by rememberUpdatedState(kidsMode)
    val currentPage by rememberUpdatedState(page)
    val voice = remember(controller) {
        VoiceUi(context).also { state ->
            val executor = VoiceCommandExecutor(VoicePlaybackAdapter(controller)) { currentPage(it) }
            state.bus = VoiceCommandBus(AndroidVoiceRecognizer(context),
                allowed = { state.attached && voiceAllowed(state.enabled, currentKids, state.foreground,
                    ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED,
                    state.available) }, now = SystemClock::elapsedRealtime, execute = executor::execute,
                feedback = { text ->
                    state.message = text
                    if (text.startsWith("Heard:")) haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                })
        }
    }
    DisposableEffect(voice, lifecycle) {
        fun refresh() {
            voice.foreground = lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
            voice.permission = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
            voice.available = SpeechRecognizer.isRecognitionAvailable(context)
            if (!voice.foreground) { voice.bus.cancel(); voice.message = "" }
        }
        voice.attached = true
        refresh()
        val observer = LifecycleEventObserver { _, _ -> refresh() }
        lifecycle.addObserver(observer)
        onDispose { voice.attached = false; lifecycle.removeObserver(observer); voice.foreground = false; voice.bus.cancel(); VoiceHeadset.start = null }
    }
    DisposableEffect(voice.enabled, voice.foreground, voice.permission, voice.available, kidsMode) {
        VoiceHeadset.start = if (voiceAllowed(voice.enabled, kidsMode, voice.foreground, voice.permission, voice.available)) ({ voice.bus.start() }) else null
        onDispose { VoiceHeadset.start = null }
    }
    return voice
}

@Composable
fun VoiceMic(voice: VoiceUi?) {
    if (voice != null && voice.enabled && voice.available && voice.permission) {
        IconButton(onClick = voice::start, enabled = voice.foreground) {
            Icon(Icons.Outlined.Mic, "Listen for one voice command")
        }
    }
}

private fun Context.activity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.activity()
    else -> null
}

@Composable
fun VoiceSettings(voice: VoiceUi) {
    val context = LocalContext.current
    var rationale by remember { mutableStateOf(false) }
    var denied by remember { mutableStateOf(false) }
    var permanent by remember { mutableStateOf(false) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        voice.permission = granted
        // A result delivered after navigation into Kids cannot enable/start a session.
        voice.changeEnabled(granted && voice.attached)
        denied = !granted
        permanent = !granted && context.activity()?.let {
            !ActivityCompat.shouldShowRequestPermissionRationale(it, Manifest.permission.RECORD_AUDIO)
        } == true
    }
    Column {
        if (!voice.available) {
            Text("Voice commands unavailable: no Android speech recognizer is installed.")
        } else {
            Row(Modifier.fillMaxWidth()) {
                Text("Voice commands · push-to-talk only", Modifier.weight(1f))
                Switch(checked = voice.enabled && voice.permission, onCheckedChange = {
                    if (it) rationale = true else voice.changeEnabled(false)
                })
            }
            Text("Off by default. Tap the mic in Listen, or hold the headset play/pause button while Mavis is open. One command, up to 6 seconds. No wake word.")
            Text("Audio: play, pause, stop, next/previous chapter, back 30 seconds, forward a minute, sleep timer 20 minutes, cancel timer, speed up, slower, 1.5x. Reader: next/previous page. Define, bookmark and read-from-here are not available by voice.")
            if (denied || (voice.enabled && !voice.permission)) {
                Text(if (permanent) "Microphone permission is blocked. Enable it in Android App settings, then turn Voice commands on here." else "Microphone permission denied. Voice commands remain off; turn on to retry.")
                if (permanent) TextButton(onClick = {
                    context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")))
                }) { Text("Open app settings") }
            }
        }
    }
    if (rationale) AlertDialog(
        onDismissRequest = { rationale = false },
        title = { Text("Microphone for voice commands") },
        text = { Text("Mavis listens only after you tap the mic or hold a headset button, for at most 6 seconds while this app is open. No background listening. The app does not save or upload audio. Android's speech provider handles recognition: offline is preferred, but the provider may use its network service. Kids mode disables this feature.") },
        confirmButton = { TextButton(onClick = {
            rationale = false
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                voice.permission = true; voice.changeEnabled(true)
            } else permission.launch(Manifest.permission.RECORD_AUDIO)
        }) { Text("Continue") } },
        dismissButton = { TextButton(onClick = { rationale = false }) { Text("Not now") } },
    )
}
