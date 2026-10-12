package com.mavislibrary.app.voice

import android.content.Context
import android.os.Bundle
import android.os.Looper
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.test.core.app.ApplicationProvider
import java.time.Duration
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSpeechRecognizer

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AndroidVoiceRecognizerTest {
    @Test fun `requests offline single final result and destroys recognizer at six seconds`() {
        val r = AndroidVoiceRecognizer(ApplicationProvider.getApplicationContext<Context>())
        val results = mutableListOf<String?>()
        r.start { results += it }
        shadowOf(Looper.getMainLooper()).idle()
        val shadow = shadowOf(ShadowSpeechRecognizer.getLatestSpeechRecognizer())
        val intent = shadow.lastRecognizerIntent
        assertTrue(intent.getBooleanExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, false))
        assertFalse(intent.getBooleanExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true))
        assertEquals(1, intent.getIntExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 0))
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(5_999))
        assertFalse(shadow.isDestroyed); assertTrue(results.isEmpty())
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1))
        assertTrue(shadow.isDestroyed); assertEquals(listOf<String?>(null), results)
    }
    @Test fun `only first final result is delivered and partial results never execute`() {
        val r = AndroidVoiceRecognizer(ApplicationProvider.getApplicationContext<Context>())
        val results = mutableListOf<String?>()
        r.start { results += it }
        shadowOf(Looper.getMainLooper()).idle()
        val shadow = shadowOf(ShadowSpeechRecognizer.getLatestSpeechRecognizer())
        val bundle = Bundle().apply { putStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION, arrayListOf("pause", "play")) }
        shadow.triggerOnPartialResults(bundle); assertTrue(results.isEmpty())
        shadow.triggerOnResults(bundle)
        assertEquals(listOf("pause"), results); assertTrue(shadow.isDestroyed)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(6))
        assertEquals(1, results.size)
    }
    @Test fun `cancel destroys recording and invalidates late provider results`() {
        val r = AndroidVoiceRecognizer(ApplicationProvider.getApplicationContext<Context>())
        var delivered = false
        r.start { delivered = true }
        shadowOf(Looper.getMainLooper()).idle()
        val shadow = shadowOf(ShadowSpeechRecognizer.getLatestSpeechRecognizer())
        r.cancel()
        shadow.triggerOnResults(Bundle().apply { putStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION, arrayListOf("play")) })
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(6))
        assertTrue(shadow.isDestroyed); assertFalse(delivered)
    }
}
