package com.mavislibrary.app.ui.podcast

import com.mavislibrary.app.data.podcast.PodcastJob
import com.mavislibrary.app.data.podcast.PodcastStory
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class EpisodeLabelsTest {
    private val zone = ZoneId.of("America/New_York")
    private val now = Instant.parse("2026-10-29T19:00:00Z") // Thu Oct 29, 3 PM in New York
    private fun job(title: String, createdAt: String = "2026-10-29T10:44:00Z", sites: List<String> = emptyList(), stories: List<PodcastStory> = emptyList(), duration: Int? = 372) =
        PodcastJob("j", "ready", title = title, kind = "chapter", style = "deep-dive", durationSec = duration, createdAt = createdAt, sites = sites, stories = stories)
    private val stories = listOf(
        PodcastStory("Google Expands AI Mode Ads", "Future Tools", "https://example.org/a"),
        PodcastStory("Anthropic Launches Claude for Legal", "Future Tools"),
        PodcastStory("New Zelda Trailer", "IGN"),
        PodcastStory("Coral Reefs Recover", "ScienceDaily"),
    )

    @Test fun headlineDropsTheDayFromNewTitles() {
        assertEquals("Google Expands AI Mode Ads…, Anthropic Launches Claude… + 2 more",
            episodeHeadline(job("Thu, Oct 29 · Google Expands AI Mode Ads…, Anthropic Launches Claude… + 2 more")))
        assertEquals("Mon, Nov 2 only", episodeHeadline(job("Mon, Nov 2 only")))
    }

    @Test fun oldDailyBriefingIsNamedFromItsStories() {
        assertEquals("Google Expands AI Mode Ads, Anthropic Launches Claude for Legal + 2 more", episodeHeadline(job("Daily briefing", stories = stories)))
        assertEquals("Google Expands AI Mode Ads", episodeHeadline(job("Daily briefing", stories = stories.take(1))))
        assertEquals("Daily briefing", episodeHeadline(job("Daily briefing")))
        assertEquals("Chapter 3", episodeHeadline(job("Chapter 3", stories = stories)))
    }

    @Test fun whenSaysTodayYesterdayOrTheDate() {
        assertEquals("Today 6:44 AM", episodeWhen(job("x"), now, zone))
        assertEquals("Yesterday 6:44 AM", episodeWhen(job("x", "2026-10-28T10:44:00Z"), now, zone))
        assertEquals("Mon, Oct 26", episodeWhen(job("x", "2026-10-26T10:44:00Z"), now, zone))
        // 1 AM UTC on the 29th is still the 28th in New York.
        assertEquals("Yesterday 9:00 PM", episodeWhen(job("x", "2026-10-29T01:00:00Z"), now, zone))
        assertEquals("", episodeWhen(job("x", ""), now, zone))
        assertEquals("", episodeWhen(job("x", "not a date"), now, zone))
    }

    @Test fun sourcesShowThreeNamesThenACount() {
        assertEquals("Future Tools · IGN · ScienceDaily", episodeSources(job("x", sites = listOf("Future Tools", "IGN", "ScienceDaily"))))
        assertEquals("A · B · C +2", episodeSources(job("x", sites = listOf("A", "B", "C", "D", "E"))))
        assertEquals("Future Tools · IGN · ScienceDaily", episodeSources(job("x", stories = stories)))
        assertEquals("", episodeSources(job("x")))
    }

    @Test fun subtitleJoinsWhatIsKnown() {
        assertEquals("Today 6:44 AM · Future Tools · IGN · 6 min", episodeSubtitle(job("x", sites = listOf("Future Tools", "IGN")), now, zone))
        assertEquals("Today 6:44 AM · 6 min", episodeSubtitle(job("Daily briefing"), now, zone))
        assertEquals("IGN", episodeSubtitle(job("x", "", sites = listOf("IGN"), duration = null), now, zone))
        assertEquals("", episodeSubtitle(job("x", "bad", duration = null), now, zone))
    }
}
