package com.mavislibrary.app.voice

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class VoiceUiTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    @Before fun clear() { context.getSharedPreferences("voice_commands", Context.MODE_PRIVATE).edit().clear().commit() }
    @Test fun `fresh settings fail closed without starting recognizer`() {
        val state = VoiceUi(context)
        assertFalse(state.enabled); assertFalse(state.foreground); assertFalse(state.permission); assertFalse(state.available)
    }
    @Test fun `turning off cancels and invalidates an in-flight result and persists opt out`() {
        val state = VoiceUi(context)
        var result: ((String?) -> Unit)? = null
        var canceled = 0; var executed = 0
        state.bus = VoiceCommandBus(object : VoiceRecognizer {
            override fun start(callback: (String?) -> Unit) { result = callback }
            override fun cancel() { canceled++ }
        }, { state.enabled }, { 0 }, { executed++; null }, {})
        state.changeEnabled(true)
        assertTrue(VoiceUi(context).enabled)
        state.start()
        state.changeEnabled(false)
        result?.invoke("play")
        assertEquals(0, executed); assertEquals(1, canceled)
        assertFalse(VoiceUi(context).enabled)
    }
    @Test fun `entering kids mode between capture and result blocks execution`() {
        var kids = false; var result: ((String?) -> Unit)? = null; var executed = 0
        val bus = VoiceCommandBus(object : VoiceRecognizer {
            override fun start(callback: (String?) -> Unit) { result = callback }
            override fun cancel() = Unit
        }, { voiceAllowed(true, kids, true, true, true) }, { 0 }, { executed++; null }, {})
        assertTrue(bus.start()); kids = true; result?.invoke("next chapter")
        assertEquals(0, executed); assertFalse(bus.start())
    }
}
