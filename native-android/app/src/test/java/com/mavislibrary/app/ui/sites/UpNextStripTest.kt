package com.mavislibrary.app.ui.sites

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.mavislibrary.app.data.podcast.PodcastJob
import com.mavislibrary.app.data.podcast.PodcastStory
import com.mavislibrary.app.ui.reader.ReaderTarget
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant
import java.time.ZoneId

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class UpNextStripTest {
    @get:Rule val compose = createComposeRule()
    private val now = Instant.parse("2026-10-29T19:00:00Z")
    private val utc = ZoneId.of("UTC")
    private val briefing = PodcastJob("b1", "ready", title = "Thu, Oct 29 · Google Expands AI Mode Ads + 1 more", kind = "chapter", style = "deep-dive",
        durationSec = 372, createdAt = "2026-10-29T06:44:00Z", sites = listOf("Future Tools", "IGN"),
        stories = listOf(PodcastStory("Google Expands AI Mode Ads", "Future Tools", "https://example.org/a"), PodcastStory("Zelda Trailer", "IGN")))
    private val old = PodcastJob("o1", "ready", title = "Daily briefing", kind = "chapter", style = "deep-dive", durationSec = 300, createdAt = "2026-10-26T06:44:00Z")

    @Test fun cardShowsHeadlineAndSubtitleAndPlayStillPlays() {
        val played = mutableListOf<List<PodcastJob>>()
        compose.setContent { UpNextStrip(listOf(briefing), 4, { played += it }, {}, now = now, zone = utc) }
        compose.onNodeWithTag("up_next_headline_b1", useUnmergedTree = true).assertTextEquals("Google Expands AI Mode Ads + 1 more")
        compose.onNodeWithTag("up_next_subtitle_b1", useUnmergedTree = true).assertTextEquals("Today 6:44 AM · Future Tools · IGN · 6 min")
        compose.onNodeWithTag("play_up_next_b1").performClick()
        assertEquals(listOf(listOf(briefing)), played)
        compose.onNodeWithTag("episode_details").assertDoesNotExist()
    }

    @Test fun tappingTheCardOpensStoriesThatOpenInTheReader() {
        var opened: ReaderTarget? = null
        val played = mutableListOf<List<PodcastJob>>()
        compose.setContent { UpNextStrip(listOf(briefing), 0, { played += it }, {}, onOpenReader = { opened = it }, now = now, zone = utc) }
        compose.onNodeWithTag("up_next_b1").performClick()
        compose.waitUntil(5_000) { compose.onAllNodes(hasTestTag("episode_details")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("In this episode").assertExists()
        compose.onNodeWithText("Zelda Trailer").assertExists()
        compose.onNodeWithTag("episode_story_0").performClick()
        assertEquals(ReaderTarget("Google Expands AI Mode Ads", "https://example.org/a", "Future Tools"), opened)
        assertTrue(played.isEmpty())
    }

    @Test fun oldEpisodeSheetSaysStoriesWereNotSaved() {
        val played = mutableListOf<List<PodcastJob>>()
        compose.setContent { UpNextStrip(listOf(old), 0, { played += it }, {}, now = now, zone = utc) }
        compose.onNodeWithTag("up_next_subtitle_o1", useUnmergedTree = true).assertTextEquals("Mon, Oct 26 · 5 min")
        compose.onNodeWithTag("up_next_o1").performClick()
        compose.waitUntil(5_000) { compose.onAllNodes(hasTestTag("episode_no_stories")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Story list not saved for this episode.").assertExists()
        compose.onNodeWithTag("episode_details_play").performClick()
        assertEquals(listOf(listOf(old)), played)
    }
}
