package com.mavislibrary.app.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import java.time.LocalTime

class LookChoiceTest {
    @Test
    fun storedValuesRoundTrip() {
        val all = listOf(LookChoice.Automatic) + Look.selectable.map { LookChoice.Fixed(it) }
        all.forEach { assertEquals(it, LookChoice.parse(it.storedValue())) }
    }

    @Test
    fun unknownOrKidsFallsBackToAutomatic() {
        assertEquals(LookChoice.Automatic, LookChoice.parse(null))
        assertEquals(LookChoice.Automatic, LookChoice.parse("NEON"))
        assertEquals(LookChoice.Automatic, LookChoice.parse("KIDS"))
    }

    @Test
    fun automaticIsPaperByDayMidnightInEvening() {
        assertEquals(Look.PAPER, LookChoice.Automatic.resolve(LocalTime.of(7, 0)))
        assertEquals(Look.PAPER, LookChoice.Automatic.resolve(LocalTime.of(18, 59)))
        assertEquals(Look.MIDNIGHT, LookChoice.Automatic.resolve(LocalTime.of(19, 0)))
        assertEquals(Look.MIDNIGHT, LookChoice.Automatic.resolve(LocalTime.of(6, 59)))
        assertEquals(Look.EDITORIAL, LookChoice.Fixed(Look.EDITORIAL).resolve(LocalTime.of(23, 0)))
    }

    @Test
    fun everyLookHasDistinctTokens() {
        assertEquals(Look.entries.size, Look.entries.map { colorSchemeFor(it).background }.distinct().size)
        assertNotEquals(tokensFor(Look.PAPER).isDark, tokensFor(Look.MIDNIGHT).isDark)
        assertEquals(72f, tokensFor(Look.KIDS).minTouchTarget.value)
    }
}
