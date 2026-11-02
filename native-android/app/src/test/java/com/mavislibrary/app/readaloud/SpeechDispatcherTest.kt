package com.mavislibrary.app.readaloud

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeechDispatcherTest {
    private class FakeEngine(val canPause: Boolean = true) : SpeechEngine {
        val spoken = mutableListOf<SpeechRequest>()
        var callback: SpeechCallback? = null
        var stops = 0
        var pauses = 0
        var resumes = 0
        override fun speak(request: SpeechRequest, callback: SpeechCallback) { spoken += request; this.callback = callback }
        override fun stop() { stops++ }
        override fun pause(): Boolean { pauses++; return canPause }
        override fun resume() { resumes++ }
    }

    private val engine = FakeEngine()
    private val events = mutableListOf<SpeechEvent>()
    private val active = mutableListOf<Boolean>()
    private fun dispatcher(target: FakeEngine = engine, backup: FakeEngine? = null) =
        SpeechDispatcher({ target }, { if (it === target) backup else null }, { events += it }, { active += it })
    private fun request(id: Long, text: String = "Hello there.") = SpeechRequest(id, text)

    @Test fun speakQueuesAndPlaysOneAtATime() {
        val d = dispatcher()
        d.speak(request(1))
        d.speak(request(2))
        assertEquals(listOf(1L), engine.spoken.map { it.id })
        engine.callback!!.onStart()
        engine.callback!!.onDone()
        assertEquals(listOf(1L, 2L), engine.spoken.map { it.id })
        assertEquals(listOf(SpeechEvent.Start(1), SpeechEvent.End(1)), events)
        engine.callback!!.onDone()
        assertEquals(listOf(true, false), active)
    }

    @Test fun cancelMatchesChromeAndFiresNoEnd() {
        val d = dispatcher()
        d.speak(request(1))
        d.speak(request(2))
        d.speak(request(3))
        engine.callback!!.onStart()
        events.clear()
        d.cancel()
        assertEquals(1, engine.stops)
        assertEquals(listOf(SpeechEvent.Error(1, "interrupted"), SpeechEvent.Error(2, "canceled"), SpeechEvent.Error(3, "canceled")), events)
        assertEquals(1, engine.spoken.size)
    }

    @Test fun lateCallbacksFromACancelledUtteranceAreIgnored() {
        val d = dispatcher()
        d.speak(request(1))
        val stale = engine.callback!!
        d.cancel()
        d.speak(request(2))
        events.clear()
        stale.onStart()
        stale.onBoundary("word", 0, 2)
        stale.onDone()
        stale.onError("synthesis-failed")
        assertTrue(events.isEmpty())
        assertEquals(2L, engine.spoken.last().id)
    }

    @Test fun pauseInPlaceHoldsTheQueueUntilResume() {
        val d = dispatcher()
        d.speak(request(1))
        engine.callback!!.onStart()
        d.pause()
        d.speak(request(2))
        assertEquals(1, engine.pauses)
        d.resume()
        assertEquals(1, engine.resumes)
        assertEquals(1, engine.spoken.size)
        engine.callback!!.onDone()
        assertEquals(2L, engine.spoken.last().id)
        assertTrue(SpeechEvent.Pause(1) in events && SpeechEvent.Resume(1) in events)
    }

    @Test fun enginesThatCannotPauseReplayFromTheLastBoundary() {
        val phone = FakeEngine(canPause = false)
        val d = dispatcher(phone)
        d.speak(request(1, "First part. Second part."))
        val stale = phone.callback!!
        stale.onStart()
        stale.onBoundary("sentence", 12, 12)
        d.pause()
        stale.onDone()
        assertEquals(1, phone.spoken.size)
        d.resume()
        assertEquals("Second part.", phone.spoken.last().text)
        phone.callback!!.onStart()
        phone.callback!!.onBoundary("word", 7, 5)
        assertEquals(SpeechEvent.Boundary(1, "word", 19, 5), events.last())
        assertEquals(1, events.count { it is SpeechEvent.Start })
    }

    @Test fun cancelClearsPausedSoTheNextSpeakPlays() {
        val d = dispatcher()
        d.pause()
        d.cancel()
        d.speak(request(1))
        assertEquals(1, engine.spoken.size)
    }

    @Test fun anEngineFailingBeforeSpeechFallsBackOnce() {
        val backup = FakeEngine()
        val d = dispatcher(engine, backup)
        d.speak(request(1))
        engine.callback!!.onError("synthesis-unavailable")
        assertEquals(1, backup.spoken.size)
        assertTrue(events.isEmpty())
        backup.callback!!.onError("synthesis-failed")
        assertEquals(listOf<SpeechEvent>(SpeechEvent.Error(1, "synthesis-failed")), events)
    }

    @Test fun failureAfterSpeechStartedReportsAndMovesOn() {
        val backup = FakeEngine()
        val d = dispatcher(engine, backup)
        d.speak(request(1))
        d.speak(request(2))
        engine.callback!!.onStart()
        engine.callback!!.onError("audio-hardware")
        assertTrue(backup.spoken.isEmpty())
        assertEquals(SpeechEvent.Error(1, "audio-hardware"), events.last())
        assertEquals(2L, engine.spoken.last().id)
    }

    @Test fun blankTextStartsAndEndsWithoutAnEngine() {
        dispatcher().speak(request(1, "   "))
        assertTrue(engine.spoken.isEmpty())
        assertEquals(listOf(SpeechEvent.Start(1), SpeechEvent.End(1)), events)
    }

    @Test fun resetDropsEverythingSilently() {
        val d = dispatcher()
        d.speak(request(1))
        d.speak(request(2))
        d.reset()
        assertEquals(1, engine.stops)
        assertTrue(events.isEmpty())
    }

    @Test fun splitsSentencesWithOriginalOffsets() {
        val text = "In the beginning. God said, \"Let there be light!\" And 3.5 cubits…\nNext line ... "
        val parts = splitSentences(text).map { text.substring(it) }
        assertEquals(listOf("In the beginning.", "God said, \"Let there be light!\"", "And 3.5 cubits…", "Next line ..."), parts)
        assertTrue(splitSentences("... -- !").isEmpty())
    }

    @Test fun longSentencesBreakAtSpaces() {
        val text = (1..100).joinToString(" ") { "word$it" }
        val spans = splitSentences(text, 60)
        assertTrue(spans.all { it.last - it.first + 1 <= 60 })
        assertEquals(text, spans.joinToString(" ") { text.substring(it) })
    }
}
