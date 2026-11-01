package com.mavislibrary.app.ui.lounge

import com.mavislibrary.app.data.podcast.PodcastJob
import com.mavislibrary.app.data.podsub.*
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class Listen085Test {
    private val now = Instant.parse("2026-11-01T14:00:00Z")
    private val doc = SubscriptionDocument(
        sites = listOf(SiteRule("ai", "AI", "https://example.com/rss", "https://example.com/"),
            SiteRule("own", "Own", "https://own.example.com/rss", "https://own.example.com/", delivery = "separate")),
        schedule = SubscriptionSchedule(true, "06:44", timezone = "America/New_York"),
        devotionals = listOf(DevotionalRule("td", "site", "Today Daily Devotional", url = "https://todaydevotional.com/daily-devotional-library"),
            DevotionalRule("anx", "topic", "Anxiety", topic = "Anxiety", enabled = false)),
        devotionalSchedule = SubscriptionSchedule(true, "19:00", timezone = "America/New_York"),
    )
    private fun job(id: String, status: String = "ready") = PodcastJob(id, status, title = id, kind = "chapter", style = "deep-dive")

    @Test fun yourDailyHasBriefingThenEachDevotionalWithNextTimeAndLatestReady() {
        val state = PodsubUiState(document = doc, nextRunAt = "2026-11-02T10:44:00Z", nextDevotionalAt = "2026-11-01T23:00:00Z",
            lastRun = SubscriptionRun("s", status = "ok", episodes = listOf(RunEpisode("b1", "Briefing", listOf("ai")))),
            lastDevotionals = mapOf("td" to DevotionalEpisode("d1", "Alpha and Omega", "Revelation 22:12-21", "c")))
        val cards = dailyCards(state, listOf(job("b1"), job("d1", "writing")), now)
        assertEquals(listOf("Morning Briefing", "Today Daily Devotional", "Anxiety"), cards.map { it.title })
        assertEquals("6:44 AM", cards[0].time)
        assertEquals("next: tomorrow 6:44 AM", cards[0].next)
        assertEquals("b1", cards[0].playable?.id)
        assertEquals("next: tonight 7:00 PM", cards[1].next)
        assertNull("Play is hidden until the latest episode is ready", cards[1].playable)
        assertEquals("Paused", cards[2].next)
    }

    @Test fun noBriefingCardWithoutBriefingSitesAndNothingBeforeLoad() {
        assertTrue(dailyCards(PodsubUiState(), emptyList(), now).isEmpty())
        val cards = dailyCards(PodsubUiState(document = doc.copy(sites = emptyList(), devotionals = emptyList())), emptyList(), now)
        assertTrue(cards.isEmpty())
    }
}
