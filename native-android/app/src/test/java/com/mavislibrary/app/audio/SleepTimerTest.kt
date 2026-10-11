package com.mavislibrary.app.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SleepTimerTest {
    @Test fun `preset starts against monotonic time`() {
        val state = SleepTimer.afterMinutes(15, 1_000)
        assertEquals(SleepTarget.At(901_000), state.target)
    }

    @Test fun `timer begins fade once then pauses at deadline`() {
        val initial = SleepTimer.afterMinutes(15, 0)
        val (fading, first) = SleepTimer.tick(initial, 890_000)
        assertEquals(SleepTimerEffect.BeginFade, first)
        assertTrue(fading.fading)
        assertEquals(SleepTimerEffect.None, SleepTimer.tick(fading, 895_000).second)
        val (done, effect) = SleepTimer.tick(fading, 900_000)
        assertEquals(SleepTimerEffect.Pause, effect)
        assertFalse(done.active)
    }

    @Test fun `end of chapter ignores ticks and stops at boundary`() {
        val state = SleepTimer.endOfChapter()
        assertEquals(SleepTimerEffect.None, SleepTimer.tick(state, Long.MAX_VALUE).second)
        assertEquals(SleepTimerEffect.Pause, SleepTimer.chapterEnded(state).second)
    }

    @Test fun `shake extends active timer by ten minutes and clears fade`() {
        val state = SleepTimerState(SleepTarget.At(2_000), fading = true)
        assertEquals(SleepTimerState(SleepTarget.At(602_000)), SleepTimer.extendFromShake(state, 1_000))
        assertEquals(SleepTimerState(SleepTarget.At(601_000)), SleepTimer.extendFromShake(SleepTimer.endOfChapter(), 1_000))
        assertEquals(SleepTimerState(), SleepTimer.extendFromShake(SleepTimer.cancel(), 1_000))
    }
}
