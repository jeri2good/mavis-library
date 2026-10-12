package com.mavislibrary.app.voice

import org.junit.Assert.*
import org.junit.Test

class VoiceCommandExecutorTest {
    private class Playback : VoicePlayback {
        override var ready = true
        override var chapterIndex = 1
        override var chapterCount = 3
        override var speed = 1f
        val calls = mutableListOf<String>()
        override fun play() { calls += "play" }
        override fun pause() { calls += "pause" }
        override fun stop() { calls += "stop" }
        override fun chapter(index: Int) { calls += "chapter:$index" }
        override fun seekBy(ms: Long) { calls += "seek:$ms" }
        override fun speed(value: Float) { speed = value; calls += "speed:$value" }
        override fun timer(minutes: Int) { calls += "timer:$minutes" }
        override fun cancelTimer() { calls += "cancel" }
    }
    @Test fun `maps all audio commands with explicit play and pause`() {
        val p = Playback(); val executor = VoiceCommandExecutor(p)
        listOf(Command.Play, Command.Pause, Command.Stop, Command.Chapter(1), Command.Chapter(-1),
            Command.Seek(-30_000), Command.Seek(60_000), Command.Timer(20), Command.CancelTimer,
            Command.Speed(.25f, true), Command.Speed(1.5f)).forEach { assertNull(executor.execute(it)) }
        assertEquals(listOf("play", "pause", "stop", "chapter:2", "chapter:0", "seek:-30000", "seek:60000",
            "timer:20", "cancel", "speed:1.25", "speed:1.5"), p.calls)
    }
    @Test fun `clamps speed and refuses chapter edges or absent audio`() {
        val p = Playback(); val e = VoiceCommandExecutor(p)
        p.speed = 2f; e.execute(Command.Speed(.25f, true)); assertEquals(2f, p.speed)
        p.speed = .75f; e.execute(Command.Speed(-.25f, true)); assertEquals(.75f, p.speed)
        p.chapterIndex = 0; assertNotNull(e.execute(Command.Chapter(-1)))
        p.chapterIndex = 2; assertNotNull(e.execute(Command.Chapter(1)))
        p.ready = false; assertNotNull(e.execute(Command.Play))
        assertEquals(2, p.calls.size)
    }
    @Test fun `reader pages use explicit hook and unavailable bookmark does not pretend success`() {
        val p = Playback(); p.ready = false; val pages = mutableListOf<Boolean>()
        val e = VoiceCommandExecutor(p) { pages += it; true }
        assertNull(e.execute(Command.Page(true))); assertEquals(listOf(true), pages)
        assertNotNull(e.execute(Command.Bookmark)); assertTrue(p.calls.isEmpty())
        assertNotNull(VoiceCommandExecutor(p).execute(Command.Page(false)))
    }
    @Test fun `twenty minute sleep uses existing timer state machine`() {
        val state = com.mavislibrary.app.audio.SleepTimer.afterMinutes(20, 100)
        assertEquals(com.mavislibrary.app.audio.SleepTarget.At(1_200_100), state.target)
    }
}
