package com.mavislibrary.app.data.kids

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.time.*

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class KidsPrefsTest {
    @get:Rule val tmp = TemporaryFolder()
    private fun TestScope.store() = PreferenceDataStoreFactory.create(scope = backgroundScope) { tmp.newFile().also { it.delete() }.let { java.io.File(it.path + ".preferences_pb") } }
    @Test fun pinMustBeExactlyFourAsciiDigits() {
        listOf("0000", "1234", "9876").forEach { assertTrue(KidsPin.valid(it)) }
        listOf("", "123", "12345", "abcd", " 1234", "１２３４", "1\n23").forEach { assertFalse(KidsPin.valid(it)) }
    }
    @Test fun saltedHashVerifiesWithoutPlaintext() {
        val hash = KidsPin.create("1234")
        assertNotEquals("1234", hash)
        assertEquals(97, hash.length)
        assertNotEquals(hash, KidsPin.create("1234"))
        assertTrue(KidsPin.verifies("1234", hash)); assertFalse(KidsPin.verifies("1235", hash))
        assertFalse(KidsPin.verifies("1234", ""))
    }
    @Test fun persistedModeAndFiveMissLockoutSurviveNewWrapper() = runTest(UnconfinedTestDispatcher()) {
        val ds = store(); var now = 1000L
        val prefs = KidsPrefs(ds) { now }
        prefs.start("  Sam  ", 20, "1234")
        repeat(4) { assertEquals(PinResult.Wrong, prefs.leave("0000")) }
        assertEquals(PinResult.Locked(30), prefs.leave("0000"))
        val reopened = KidsPrefs(ds) { now }
        assertEquals(PinResult.Locked(30), reopened.leave("1234"))
        assertTrue(reopened.state.first().enabled)
        now += 29999; assertEquals(PinResult.Locked(1), reopened.leave("1234"))
        now++; assertEquals(PinResult.Correct, reopened.leave("1234"))
        assertFalse(reopened.state.first().enabled)
        assertEquals("Sam", reopened.state.first().name)
        assertEquals(20, reopened.state.first().goal)
        assertFalse(ds.data.first().asMap().values.any { it == "1234" })
    }
    @Test fun missingPinDoesNotUnlock() = runTest(UnconfinedTestDispatcher()) {
        assertEquals(PinResult.Wrong, KidsPrefs(store()).leave("1234"))
    }
    @Test fun correctPinClearsMissesAndExpiredLockStartsFresh() = runTest(UnconfinedTestDispatcher()) {
        val ds = store(); var now = 500L
        val prefs = KidsPrefs(ds) { now }
        prefs.start("Sam", 15, "1234")
        repeat(4) { assertEquals(PinResult.Wrong, prefs.leave("0000")) }
        assertEquals(PinResult.Correct, prefs.leave("1234"))
        prefs.start("Sam", 15, "1234")
        assertEquals(PinResult.Wrong, prefs.leave("0000"))
        repeat(4) { prefs.leave("0000") }
        assertEquals(PinResult.Locked(30), prefs.leave("1234"))
        now += 30000
        assertEquals(PinResult.Wrong, prefs.leave("0000"))
        assertEquals(PinResult.Correct, prefs.leave("1234"))
    }
    @Test fun goalsAndDefault() = runTest(UnconfinedTestDispatcher()) {
        val prefs = KidsPrefs(store()); assertEquals(15, prefs.state.first().goal)
        assertTrue(runCatching { prefs.start("Sam", 12, "1234") }.isFailure)
        assertTrue(runCatching { prefs.start("Sam", 15, "12") }.isFailure)
        assertFalse(prefs.state.first().enabled)
    }
    @Test fun streakAndWeekMatchWebAcrossMidnight() {
        val today = LocalDate.of(2026, 10, 11)
        val s = KidsState(log = mapOf(today.minusDays(2) to 900000, today.minusDays(1) to 900000, today to 899999))
        assertEquals(2, s.streak(today)); assertEquals(0, s.streak(today.plusDays(1)))
        assertEquals(7, s.week(today).size); assertEquals(today.minusDays(6), s.week(today).first().date)
        assertEquals(14, s.week(today).last().minutes); assertFalse(s.week(today).last().star)
        val done = s.copy(log = s.log + (today to 900000L))
        assertEquals(3, done.streak(today)); assertEquals(3, done.streak(today.plusDays(1)))
        assertTrue(done.week(today).last().star)
    }
    @Test fun filterMatchesWebRules() {
        assertEquals(21, KIDS_SHELF.size)
        assertTrue(kidsBook("gutenberg", "11")); assertFalse(kidsBook("openlibrary", "11"))
        assertTrue(kidsBook("import", "x", kids = true))
        assertTrue(kidsBook("gutenberg", "999", subjects = listOf("Juvenile fiction")))
        assertTrue(kidsBook("gutenberg", "999", shelves = listOf("Children's books")))
        assertFalse(kidsBook("gutenberg", "999", subjects = listOf("Fiction")))
    }
    @Test fun accumulatorSplitsMidnightAndDoesNotCountPausedTime() {
        var now = Instant.parse("2026-10-12T03:59:30Z")
        val a = ReadingAccumulator({ now }, { ZoneId.of("America/New_York") })
        a.update(true); now = now.plusSeconds(90); a.update(false)
        assertEquals(mapOf(LocalDate.of(2026,10,11) to 30000L, LocalDate.of(2026,10,12) to 60000L), a.drain())
        now = now.plusSeconds(100); a.update(false); assertTrue(a.drain().isEmpty())
        a.update(true); now = now.plusSeconds(10); a.update(true); now = now.plusSeconds(20); a.update(false)
        assertEquals(30000L, a.drain().values.single()); assertTrue(a.drain().isEmpty())
    }
    @Test fun accumulatorHandlesDaylightSavingAndBackwardClock() {
        var now = Instant.parse("2026-11-01T04:00:00Z")
        val a = ReadingAccumulator({ now }, { ZoneId.of("America/New_York") })
        a.update(true); now = now.plusSeconds(25 * 3600L); a.update(false)
        assertEquals(25 * 3600000L, a.drain().values.single())
        a.update(true); now = now.minusSeconds(20); a.update(false); assertTrue(a.drain().isEmpty())
    }
    @Test fun logKeepsFractionsAndLast120Dates() = runTest(UnconfinedTestDispatcher()) {
        val prefs = KidsPrefs(store()); val date = LocalDate.of(2026,10,11)
        prefs.addTime((0..120).associate { date.minusDays(it.toLong()) to 30000L })
        prefs.addTime(mapOf(date to 30000L))
        val s = prefs.state.first(); assertEquals(120, s.log.size); assertEquals(1, s.minutes(date))
    }
    @Test fun adultRoutesUnreachableInKidsMode() {
        assertFalse(adultRoutesAllowed(true)); assertTrue(adultRoutesAllowed(false))
    }
}
