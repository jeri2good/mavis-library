package com.mavislibrary.app.work

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId

class ReadingReminderTest {
    private val newYork = ZoneId.of("America/New_York")
    private fun at(instant: String, zone: ZoneId = newYork) = Clock.fixed(Instant.parse(instant), zone)

    @Test fun nextFireCrossesMidnight() {
        val target = LocalTime.of(20, 0)
        assertEquals(Instant.parse("2026-10-11T00:00:00Z"), nextReminderFire(target, at("2026-10-10T23:59:00Z")))
        assertEquals(Instant.parse("2026-10-12T00:00:00Z"), nextReminderFire(target, at("2026-10-11T00:00:00Z")))
    }

    @Test fun springGapMovesToFirstValidLocalTime() {
        assertEquals(Instant.parse("2026-03-08T07:30:00Z"),
            nextReminderFire(LocalTime.of(2, 30), at("2026-03-08T06:00:00Z")))
        assertEquals(Instant.parse("2026-03-09T06:30:00Z"),
            nextReminderFire(LocalTime.of(2, 30), at("2026-03-08T08:00:00Z")))
    }

    @Test fun fallOverlapFiresOnlyOnceThatDay() {
        assertEquals(Instant.parse("2026-11-01T05:30:00Z"),
            nextReminderFire(LocalTime.of(1, 30), at("2026-11-01T04:59:00Z")))
        assertEquals(Instant.parse("2026-11-02T06:30:00Z"),
            nextReminderFire(LocalTime.of(1, 30), at("2026-11-01T05:45:00Z")))
    }

    @Test fun skippedWhenDisabledOrKidsOrPermissionMissing() {
        assertTrue(shouldSendReminder(true, false, true))
        assertFalse(shouldSendReminder(false, false, true))
        assertFalse(shouldSendReminder(true, true, true))
        assertFalse(shouldSendReminder(true, false, false))
    }
}
