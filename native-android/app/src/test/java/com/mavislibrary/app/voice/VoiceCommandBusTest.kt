package com.mavislibrary.app.voice

import org.junit.Assert.*
import org.junit.Test

class VoiceCommandBusTest {
    private class Recognizer : VoiceRecognizer {
        val results = mutableListOf<(String?) -> Unit>()
        var cancels = 0
        override fun start(result: (String?) -> Unit) { results += result }
        override fun cancel() { cancels++ }
    }
    @Test fun `one session one command and exact debounce boundary`() {
        val recognizer = Recognizer(); var now = 0L
        val commands = mutableListOf<Command>()
        val bus = VoiceCommandBus(recognizer, { true }, { now }, { commands += it; null }, {})
        assertTrue(bus.start()); assertFalse(bus.start())
        recognizer.results[0]("pause"); recognizer.results[0]("next chapter")
        assertEquals(listOf(Command.Pause), commands)
        now = 1499; assertFalse(bus.start())
        now = 1500; assertTrue(bus.start())
        recognizer.results[1]("resume")
        assertEquals(listOf(Command.Pause, Command.Play), commands)
    }
    @Test fun `cancel and disabled gate discard late callbacks even after restart`() {
        val r = Recognizer(); var allowed = true; var executed = 0
        val bus = VoiceCommandBus(r, { allowed }, { 0 }, { executed++; null }, {})
        bus.start(); bus.cancel(); bus.start()
        r.results[0]("play"); assertEquals(0, executed)
        allowed = false; r.results[1]("play"); assertEquals(0, executed)
        assertFalse(bus.start())
    }
    @Test fun `junk and provider failure do nothing and allow retry`() {
        val r = Recognizer(); var executed = 0; val feedback = mutableListOf<String>()
        val bus = VoiceCommandBus(r, { true }, { 0 }, { executed++; null }, { feedback += it })
        bus.start(); r.results[0]("could you stop talking")
        assertTrue(bus.start()); r.results[1](null)
        assertEquals(0, executed); assertFalse(bus.listening)
        assertTrue(feedback.any { it.contains("No matching command") })
    }
    @Test fun `kids and each missing prerequisite fail closed`() {
        for (enabled in listOf(false, true)) for (kids in listOf(false, true))
            for (foreground in listOf(false, true)) for (permission in listOf(false, true))
                for (available in listOf(false, true)) {
                    assertEquals(enabled && !kids && foreground && permission && available,
                        voiceAllowed(enabled, kids, foreground, permission, available))
                }
    }
}
