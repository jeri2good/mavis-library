package com.mavislibrary.app.readaloud

import android.content.Context
import android.media.AudioAttributes
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale

/** The phone's own speech engine. It cannot pause, so [pause] stops and the dispatcher replays from the last boundary. */
class PhoneVoiceEngine(context: Context, private val onVoices: (List<PhoneVoice>) -> Unit) : SpeechEngine {
    private val appContext = context.applicationContext
    private val lock = Any()
    private var tts: TextToSpeech? = null
    private var ready = false
    private var failed = false
    private var pending: (() -> Unit)? = null
    private var session: Session? = null
    private var sessions = 0L

    private class Session(val prefix: String, val spans: List<IntRange>, val callback: SpeechCallback) {
        var started = false
    }

    /** Connects to the system engine; voices arrive through [onVoices]. */
    fun warm() {
        synchronized(lock) {
            if (tts != null) return
            tts = TextToSpeech(appContext) { status -> onInit(status == TextToSpeech.SUCCESS) }
        }
    }

    fun refreshVoices() = synchronized(lock) {
        val engine = tts
        if (engine != null && ready) onVoices(readVoices(engine))
    }

    private fun onInit(ok: Boolean) {
        val run: (() -> Unit)?
        val voices: List<PhoneVoice>
        synchronized(lock) {
            ready = ok
            failed = !ok
            val engine = tts
            voices = if (ok && engine != null) {
                engine.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                engine.setOnUtteranceProgressListener(Progress())
                readVoices(engine)
            } else emptyList()
            run = pending
            pending = null
        }
        onVoices(voices)
        run?.invoke()
    }

    private fun readVoices(engine: TextToSpeech): List<PhoneVoice> = runCatching {
        engine.voices.orEmpty().map { PhoneVoice(it.name, it.locale.toLanguageTag(), !it.isNetworkConnectionRequired) }
    }.getOrDefault(emptyList())

    override fun speak(request: SpeechRequest, callback: SpeechCallback) {
        warm()
        // Runs outside this lock: callbacks take the dispatcher's lock, which is always taken first.
        val now: (() -> Unit)? = synchronized(lock) {
            when {
                failed -> ({ callback.onError("synthesis-unavailable") })
                !ready -> {
                    pending = { if (failed) callback.onError("synthesis-unavailable") else play(request, callback) }
                    null
                }
                else -> ({ play(request, callback) })
            }
        }
        now?.invoke()
    }

    private fun play(request: SpeechRequest, callback: SpeechCallback) {
        val engine = tts ?: return callback.onError("synthesis-unavailable")
        val name = request.voiceUri?.removePrefix(PHONE_PREFIX)
        val voice = name?.let { wanted -> runCatching { engine.voices?.firstOrNull { it.name == wanted } }.getOrNull() }
        if (voice != null) engine.voice = voice
        else engine.language = request.lang.takeIf { it.isNotBlank() }?.let(Locale::forLanguageTag) ?: Locale.getDefault()
        engine.setSpeechRate(request.rate.coerceIn(.1f, 4f))
        engine.setPitch(request.pitch.coerceIn(.1f, 2f))
        val spans = splitSentences(request.text, (TextToSpeech.getMaxSpeechInputLength() - 100).coerceAtLeast(200))
        if (spans.isEmpty()) {
            callback.onStart()
            callback.onDone()
            return
        }
        val current = Session("${++sessions}:", spans, callback)
        synchronized(lock) { session = current }
        val params = Bundle().apply { putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, request.volume.coerceIn(0f, 1f)) }
        spans.forEachIndexed { index, span ->
            val result = engine.speak(request.text.substring(span), if (index == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD, params, "${current.prefix}$index")
            if (result != TextToSpeech.SUCCESS) {
                synchronized(lock) { session = null }
                engine.stop()
                callback.onError("synthesis-failed")
                return
            }
        }
    }

    override fun stop() {
        synchronized(lock) {
            pending = null
            session = null
            tts?.stop()
        }
    }

    override fun pause(): Boolean {
        stop()
        return false
    }

    override fun resume() = Unit

    private inner class Progress : UtteranceProgressListener() {
        private fun find(utteranceId: String?): Pair<Session, Int>? = synchronized(lock) {
            val current = session ?: return null
            if (utteranceId == null || !utteranceId.startsWith(current.prefix)) return null
            val index = utteranceId.removePrefix(current.prefix).toIntOrNull() ?: return null
            current to index
        }

        override fun onStart(utteranceId: String?) {
            val (current, index) = find(utteranceId) ?: return
            if (!current.started) {
                current.started = true
                current.callback.onStart()
            }
            val span = current.spans[index]
            current.callback.onBoundary("sentence", span.first, span.last - span.first + 1)
        }

        override fun onRangeStart(utteranceId: String?, start: Int, end: Int, frame: Int) {
            val (current, index) = find(utteranceId) ?: return
            current.callback.onBoundary("word", current.spans[index].first + start, end - start)
        }

        override fun onDone(utteranceId: String?) {
            val (current, index) = find(utteranceId) ?: return
            if (index == current.spans.lastIndex) finish { current.callback.onDone() }
        }

        @Deprecated("Deprecated in Java")
        override fun onError(utteranceId: String?) = fail(utteranceId)

        override fun onError(utteranceId: String?, errorCode: Int) = fail(utteranceId)

        private fun fail(utteranceId: String?) {
            val (current, _) = find(utteranceId) ?: return
            finish { tts?.stop(); current.callback.onError("synthesis-failed") }
        }

        private fun finish(block: () -> Unit) {
            synchronized(lock) { session = null }
            block()
        }
    }

    companion object {
        const val PHONE_PREFIX = "android:"
    }
}

data class PhoneVoice(val name: String, val lang: String, val offline: Boolean)
