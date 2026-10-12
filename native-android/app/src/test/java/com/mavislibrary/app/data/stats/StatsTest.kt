package com.mavislibrary.app.data.stats

import com.mavislibrary.app.data.kids.ReadingAccumulator
import org.junit.Assert.*
import org.junit.Test
import java.time.*

class StatsTest {
    private val today = LocalDate.of(2026, 10, 11)
    private fun days(vararg offsets: Long) = ReadingStats(offsets.associate { today.plusDays(it) to ReadingDay(60000) })
    @Test fun currentStreakAllowsUnfinishedToday() {
        assertEquals(2, days(-2,-1).currentStreak(today))
        assertEquals(3, days(-2,-1,0).currentStreak(today))
        assertEquals(0, days(-2).currentStreak(today))
    }
    @Test fun longestStreakSurvivesGapsAndIgnoresFuture() {
        val stats = days(-9,-8,-7,-3,-2,0,2,3,4,5)
        assertEquals(3, stats.longestStreak(today)); assertEquals(1, stats.currentStreak(today))
    }
    @Test fun subminuteDoesNotEarnDay() {
        assertEquals(0, ReadingStats(mapOf(today to ReadingDay(59999))).currentStreak(today))
        assertEquals(0, ReadingStats().longestStreak(today))
    }
    @Test fun accumulatorSplitsMidnightWithoutDoubleCounting() {
        var now = Instant.parse("2026-10-11T03:59:00Z")
        val accumulator = ReadingAccumulator({ now }, { ZoneId.of("America/New_York") })
        accumulator.update(true); now = now.plusSeconds(120); accumulator.update(false)
        val chunk = accumulator.drain()
        assertEquals(mapOf(today.minusDays(1) to 60000L, today to 60000L), chunk)
        assertTrue(accumulator.drain().isEmpty())
        assertEquals(2, ReadingStats(chunk.mapValues { ReadingDay(it.value) }).currentStreak(today))
    }
    @Test fun weeklyRecapUsesCalendarWeekAndDistinctBooks() {
        val stats = ReadingStats(mapOf(
            today.minusDays(7) to ReadingDay(600000, setOf("old")),
            today.minusDays(6) to ReadingDay(120000, setOf("a")),
            today to ReadingDay(180000, setOf("a", "b")),
            today.plusDays(1) to ReadingDay(600000, setOf("future"))))
        assertEquals(WeeklyRecap(5,2,2), stats.recap(today))
        assertEquals(7, stats.week(today).size)
        assertEquals(today.minusDays(6), stats.week(today).first())
    }
    @Test fun goalProgressClamps() {
        assertEquals(.5f, goalProgress(75,150), 0f)
        assertEquals(1f, goalProgress(200,150), 0f)
        assertEquals(0f, goalProgress(50,0), 0f)
        assertEquals(0f, goalProgress(-1,150), 0f)
    }
}
