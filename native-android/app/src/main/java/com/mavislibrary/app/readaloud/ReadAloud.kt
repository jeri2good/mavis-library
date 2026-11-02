package com.mavislibrary.app.readaloud

import android.app.Application
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

sealed interface SpeechCommand {
    data object Hello : SpeechCommand
    data class Speak(val request: SpeechRequest) : SpeechCommand
    data object Cancel : SpeechCommand
    data object Pause : SpeechCommand
    data object Resume : SpeechCommand
}

/** Parses one message from the page's shim; null for anything else. */
fun parseSpeechCommand(json: String): SpeechCommand? = runCatching {
    val message = JSONObject(json)
    when (message.getString("op")) {
        "hello" -> SpeechCommand.Hello
        "cancel" -> SpeechCommand.Cancel
        "pause" -> SpeechCommand.Pause
        "resume" -> SpeechCommand.Resume
        "speak" -> SpeechCommand.Speak(SpeechRequest(
            id = message.getLong("id"),
            text = message.getString("text").also { require(it.length <= 32_767) },
            voiceUri = message.optString("voice").takeIf { !message.isNull("voice") && it.isNotBlank() },
            lang = message.optString("lang"),
            rate = message.optDouble("rate", 1.0).toFloat(),
            pitch = message.optDouble("pitch", 1.0).toFloat(),
            volume = message.optDouble("volume", 1.0).toFloat(),
        ))
        else -> null
    }
}.getOrNull()

fun speechEventJson(event: SpeechEvent): String = JSONObject().put("id", event.id).apply {
    when (event) {
        is SpeechEvent.Start -> put("ev", "start")
        is SpeechEvent.End -> put("ev", "end")
        is SpeechEvent.Pause -> put("ev", "pause")
        is SpeechEvent.Resume -> put("ev", "resume")
        is SpeechEvent.Error -> put("ev", "error").put("error", event.code)
        is SpeechEvent.Boundary -> put("ev", "boundary").put("name", event.name).put("charIndex", event.charIndex).put("charLength", event.charLength)
    }
}.toString()

fun voicesJson(voices: List<ShimVoice>): String = JSONObject().put("ev", "voices").put("voices", JSONArray().apply {
    voices.forEach { put(JSONObject().put("uri", it.uri).put("name", it.name).put("lang", it.lang).put("isDefault", it.isDefault)) }
}).toString()

/** App-wide read-aloud: the speech queue, its engines, audio focus, and the line to the page's shim. */
class ReadAloud(app: Application, val prefs: ReadAloudPrefs, val model: KokoroModelStore, private val scope: CoroutineScope) {
    /** False on 32-bit-only phones, which have the phone voice alone. */
    val naturalSupported = KokoroEngine.supported()
    private val main = Handler(Looper.getMainLooper())
    private val phone = PhoneVoiceEngine(app) { voices -> phoneVoices = voices; pushVoices() }
    private val kokoro by lazy { KokoroEngine(model::modelDir) { settings.naturalVoice } }
    private val audio = app.getSystemService(AudioManager::class.java)

    @Volatile private var settings = ReadAloudSettings()
    @Volatile private var phoneVoices = emptyList<PhoneVoice>()
    /** Touched on the main thread only. */
    private var pageSink: ((String) -> Unit)? = null

    private val dispatcher = SpeechDispatcher(
        route = { request -> if (engineFor(request.voiceUri, request.lang, settings, naturalInstalled()) == VoiceEngineKind.NATURAL) kokoro else phone },
        fallback = { if (it === phone) null else phone },
        emit = { event -> sendToPage(speechEventJson(event)) },
        onActiveChanged = ::onActiveChanged,
    )

    init {
        scope.launch {
            prefs.settings.collectLatest {
                settings = it
                pushVoices()
            }
        }
        scope.launch { model.state.collectLatest { pushVoices() } }
    }

    private fun naturalInstalled() = naturalSupported && model.state.value == KokoroState.Installed

    private var downloadJob: Job? = null

    /** Runs in the app scope, so it continues when the Settings screen closes. */
    fun startDownload() {
        if (downloadJob?.isActive == true) return
        downloadJob = scope.launch { model.download() }
    }

    fun cancelDownload() { downloadJob?.cancel() }

    fun removeModel() {
        dispatcher.reset()
        kokoro.release()
        scope.launch {
            downloadJob?.cancelAndJoin()
            model.remove()
        }
    }

    /** Plays a short line in one voice from the Settings screen, replacing anything being read. */
    fun sample(voiceUri: String?) {
        phone.warm()
        dispatcher.cancel()
        dispatcher.speak(SpeechRequest(sampleIds--, SAMPLE_TEXT, voiceUri, "en-US"))
    }

    fun stopSample() = dispatcher.cancel()

    private var sampleIds = -1L

    /** One message from the page. Called on the main thread by the WebView listener. */
    fun onWebMessage(message: String, reply: (String) -> Unit) {
        when (val command = parseSpeechCommand(message) ?: return) {
            SpeechCommand.Hello -> {
                pageSink = reply
                dispatcher.reset()
                phone.warm()
                pushVoices()
            }
            is SpeechCommand.Speak -> dispatcher.speak(command.request)
            SpeechCommand.Cancel -> dispatcher.cancel()
            SpeechCommand.Pause -> dispatcher.pause()
            SpeechCommand.Resume -> dispatcher.resume()
        }
    }

    fun detachPage() {
        pageSink = null
        dispatcher.reset()
    }

    private fun pushVoices() = sendToPage(voicesJson(voiceList(settings, naturalInstalled(), phoneVoices)))

    private fun sendToPage(json: String) {
        main.post { runCatching { pageSink?.invoke(json) } }
    }

    private val focusAttributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
    private val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
        .setAudioAttributes(focusAttributes)
        .setOnAudioFocusChangeListener { change ->
            // A call or another player took over. The page gets an error, so its player stops instead of queueing on.
            if (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) dispatcher.cancel("audio-busy")
        }.build()
    private val releaseFocus = Runnable { audio?.abandonAudioFocusRequest(focusRequest) }

    private fun onActiveChanged(active: Boolean) {
        main.removeCallbacks(releaseFocus)
        // The web player speaks one verse at a time, so focus outlives short gaps and other apps don't surge between verses.
        if (active) audio?.requestAudioFocus(focusRequest) else main.postDelayed(releaseFocus, FOCUS_LINGER_MS)
    }

    private companion object {
        const val FOCUS_LINGER_MS = 2_000L
        const val SAMPLE_TEXT = "The Lord is my shepherd; I shall not want."
    }
}
