package com.mavislibrary.app.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

/** No recorder, audio files, partial commands, automatic restarts, or background service. */
class AndroidVoiceRecognizer(private val context: Context) : VoiceRecognizer {
    private val handler = Handler(Looper.getMainLooper())
    private var active: SpeechRecognizer? = null
    private var deadline: Runnable? = null

    override fun start(result: (String?) -> Unit) {
        cancel()
        try {
            val recognizer = SpeechRecognizer.createSpeechRecognizer(context)
            active = recognizer
            fun finish(text: String?) {
                if (active !== recognizer) return
                cancel()
                result(text)
            }
            recognizer.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) = Unit
                override fun onBeginningOfSpeech() = Unit
                override fun onRmsChanged(rmsdB: Float) = Unit
                override fun onBufferReceived(buffer: ByteArray?) = Unit
                override fun onEndOfSpeech() = Unit
                override fun onError(error: Int) = finish(null)
                override fun onResults(results: Bundle?) = finish(results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull())
                override fun onPartialResults(partialResults: Bundle?) = Unit
                override fun onEvent(eventType: Int, params: Bundle?) = Unit
            })
            deadline = Runnable { finish(null) }.also { handler.postDelayed(it, 6_000) }
            recognizer.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            })
        } catch (_: RuntimeException) {
            cancel()
            result(null)
        }
    }

    override fun cancel() {
        deadline?.let(handler::removeCallbacks)
        deadline = null
        val old = active
        active = null
        if (old != null) {
            runCatching { old.cancel() }
            runCatching { old.destroy() }
        }
    }
}
