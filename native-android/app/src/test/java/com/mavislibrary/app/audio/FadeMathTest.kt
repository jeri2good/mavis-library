package com.mavislibrary.app.audio

import org.junit.Assert.assertEquals
import org.junit.Test

class FadeMathTest {
    @Test fun `gain is linear and clamped`() {
        assertEquals(1f, FadeMath.gain(-1_000), 0.0001f)
        assertEquals(1f, FadeMath.gain(0), 0.0001f)
        assertEquals(.5f, FadeMath.gain(5_000), 0.0001f)
        assertEquals(0f, FadeMath.gain(10_000), 0.0001f)
        assertEquals(0f, FadeMath.gain(12_000), 0.0001f)
    }
}
