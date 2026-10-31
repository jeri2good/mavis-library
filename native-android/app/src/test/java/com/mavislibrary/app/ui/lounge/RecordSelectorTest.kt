package com.mavislibrary.app.ui.lounge

import android.net.Uri
import androidx.compose.ui.test.*
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.test.junit4.createComposeRule
import com.mavislibrary.app.data.podcast.PodcastJob
import com.mavislibrary.app.data.podcast.SavedPodcast
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RecordSelectorTest {
    @get:Rule val compose = createComposeRule()

    private fun saved(id: String, status: String = "ready", created: String = "2026-10-2${id.last().digitToIntOrNull() ?: 0}T06:00:00Z", account: String? = "me",
                      studio: String = "studio", local: String? = null) =
        SavedPodcast(PodcastJob(id, status, if (status == "writing") 40.0 else 0.0, title = "Episode $id", kind = "chapter", style = "deep-dive",
            durationSec = 840, createdAt = created, error = if (status == "failed") "Studio out of voices" else null), studio, accountId = account, localFile = local)

    private val library = listOf(saved("n3"), saved("n2"), saved("p1"), saved("c4"), saved("m5", "writing"), saved("f6", "failed"))
    private val positions = mapOf("podcast:studio/p1" to 830_000L, "podcast:studio/c4" to 120_000L)
    private val shelves = gallery(library, positions)

    @Test fun chipsFilterAndDefaultToNewOrAll() {
        assertEquals(RecordFilter.NEW, defaultRecordFilter(shelves))
        assertEquals(RecordFilter.ALL, defaultRecordFilter(gallery(listOf(saved("c4")), positions)))
        assertEquals(RecordFilter.ALL, defaultRecordFilter(emptyMap()))
        assertEquals(listOf("n3", "n2"), records(RecordFilter.NEW, shelves).map { it.job.id })
        assertEquals(listOf("c4"), records(RecordFilter.CONTINUE, shelves).map { it.job.id })
        assertEquals(listOf("m5"), records(RecordFilter.MAKING, shelves).map { it.job.id })
        assertEquals(listOf("p1"), records(RecordFilter.PLAYED, shelves).map { it.job.id })
        assertEquals("All is everything, newest first, including failed", listOf("f6", "m5", "c4", "n3", "n2", "p1"),
            records(RecordFilter.ALL, shelves).map { it.job.id })
        assertTrue(records(RecordFilter.NEW, emptyMap()).isEmpty())
    }

    @Test fun visibleAndPlayableEpisodes() {
        val all = listOf(saved("a1"), saved("b2", account = "other"), saved("c3", studio = "old"), saved("d4", studio = "old", local = "/d.mp3"))
        assertEquals(listOf("d4", "a1"), visiblePodcasts(all, "me", "studio", 0).map { it.job.id })
        assertEquals(listOf("d4", "b2", "a1"), visiblePodcasts(all, "owner", "studio", 0).map { it.job.id })
        assertTrue(visiblePodcasts(all, null, "studio", 0).isEmpty())
        val queue = gallery(all, emptyMap()).getValue(PodcastShelf.NEW)
        val playable = playableQueue(queue) { s -> if (s.studio == "studio") Uri.parse("https://x/${s.job.id}") else null }
        assertEquals(listOf("a1", "b2"), playable.map { it.first.job.id })
    }

    @Test fun centredRecordPlaysWithTheUpNextQueue() {
        val played = mutableListOf<Pair<Episode, List<Episode>>>()
        val details = mutableListOf<Episode>()
        show(onPlay = { e, q -> played += e to q }, onDetails = { details += it })
        compose.onNodeWithTag("record_chip_new").assertIsSelected()
        compose.onNodeWithTag("record_headline").assertTextEquals("Episode n3")
        compose.onNodeWithTag("record_play").performClick()
        val (episode, queue) = played.single()
        assertEquals("n3", episode.job.id)
        assertEquals(listOf("n3", "n2"), queue.map { it.job.id })
        assertEquals(upNext(episode, shelves).map { it.saved.key }, queue.map { it.saved.key })

        // Tapping the centred record plays it too; its label opens the details instead.
        compose.onNodeWithTag("record_n3").performTouchInput { click(androidx.compose.ui.geometry.Offset(centerX, height * .12f)) }
        assertEquals(2, played.size)
        assertTrue(details.isEmpty())
        compose.onNodeWithTag("record_label_n3").performClick()
        assertEquals(listOf("n3"), details.map { it.job.id })
        compose.onNodeWithTag("record_info").performClick()
        assertEquals(2, details.size)
    }

    @Test fun swipingAndChipsChangeTheCentredRecord() {
        val played = mutableListOf<Pair<Episode, List<Episode>>>()
        show(onPlay = { e, q -> played += e to q })
        compose.onNodeWithTag("record_n3").performTouchInput { swipeLeft() }
        compose.waitForIdle()
        compose.onNodeWithTag("record_headline").assertTextEquals("Episode n2")
        compose.onNodeWithTag("record_play").performClick()
        assertEquals(listOf("n2"), played.single().second.map { it.job.id })

        compose.onNodeWithTag("record_chip_continue").performClick()
        compose.onNodeWithTag("record_chip_continue").assertIsSelected()
        compose.onNodeWithTag("record_headline").assertTextEquals("Episode c4")
        compose.onNodeWithText("Resume").assertExists()
        compose.onNodeWithTag("record_play").performClick()
        assertEquals("a non-new episode is followed by every new one", listOf("c4", "n3", "n2"), played.last().second.map { it.job.id })
    }

    @Test fun beingMadeAndFailedRecordsCantPlay() {
        val played = mutableListOf<Pair<Episode, List<Episode>>>()
        val details = mutableListOf<Episode>()
        show(onPlay = { e, q -> played += e to q }, onDetails = { details += it })
        compose.onNodeWithTag("record_chip_making").performClick()
        compose.onAllNodesWithText("Writing the script · 40%", useUnmergedTree = true).onFirst().assertExists()
        compose.onNodeWithTag("record_play").assertIsNotEnabled()
        compose.onNodeWithTag("record_m5").performTouchInput { click(androidx.compose.ui.geometry.Offset(centerX, height * .12f)) }
        assertEquals("tapping an unfinished record shows its details", listOf("m5"), details.map { it.job.id })
        compose.onNodeWithTag("record_chip_all").performClick()
        compose.onNodeWithTag("record_headline").assertTextEquals("Episode f6")
        compose.onNodeWithText("Couldn't be made", useUnmergedTree = true).assertExists()
        compose.onNodeWithText("Studio out of voices").assertExists()
        compose.onNodeWithTag("record_play").assertIsNotEnabled()
        assertTrue(played.isEmpty())
    }

    @Test fun loadedRecordTogglesAndMenuShowsActions() {
        var toggles = 0
        var menuFor: String? = null
        compose.setContent {
            RecordSelector(shelves, nowPlaying = "podcast:studio/n3", playing = false, enabled = true, onPlay = { _, _ -> fail("should resume") },
                onToggle = { toggles++ }, onDetails = {}, menu = { e, close -> androidx.compose.material3.DropdownMenuItem({ androidx.compose.material3.Text("Delete") },
                    { menuFor = e.job.id; close() }) })
        }
        compose.onNodeWithText("Resume").assertExists()
        compose.onNodeWithTag("record_play").performClick()
        assertEquals(1, toggles)
        compose.onNodeWithTag("record_menu").performClick()
        compose.onNodeWithText("Delete").performClick()
        assertEquals("n3", menuFor)
    }

    @Test fun upNextListsTheRestOfTheChipAndTapSelects() {
        assertEquals(listOf("n2"), upNextRows(records(RecordFilter.NEW, shelves), 0).map { it.value.job.id })
        val all = records(RecordFilter.ALL, shelves)
        assertEquals("after the centred one, then the ones before it", listOf(3 to "n3", 4 to "n2", 5 to "p1", 0 to "f6", 1 to "m5"),
            upNextRows(all, 2).map { it.index to it.value.job.id })
        assertTrue(upNextRows(all.take(1), 0).isEmpty())

        show()
        compose.onNodeWithTag("record_chip_all").performClick()
        compose.onNodeWithTag("record_next_f6").assertDoesNotExist()
        compose.onNodeWithTag("record_next_n2").assertTextContains("Episode n2").assertTextContains("14 min", substring = true)
        compose.onNodeWithTag("record_next_m5").assertTextContains("Writing the script · 40%", substring = true)
        compose.onNodeWithTag("record_next_n2").performScrollTo().performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("record_headline").assertTextEquals("Episode n2")
        compose.onNodeWithTag("record_next_n2").assertDoesNotExist()
        compose.onNodeWithTag("record_next_f6").assertExists()
    }

    @Test fun briefingHeadlineAndSubtitleHaveNoStrayCharacters() {
        val now = java.time.Instant.parse("2026-10-29T12:00:00Z")
        val job = PodcastJob("b1", "ready", title = "Thu, Oct 29 · Google Expands AI Mode Ads + 2 more", kind = "briefing", style = "deep-dive",
            durationSec = 600, createdAt = "2026-10-29T06:00:00Z", sites = listOf("Future Tools"))
        val episode = Episode(SavedPodcast(job, "studio", accountId = "me"), null)
        assertEquals("Google Expands AI Mode Ads + 2 more", com.mavislibrary.app.ui.podcast.episodeHeadline(job))
        assertEquals("Today 6:00 AM · Future Tools · 10 min · Deep dive", episodeDetails(episode, java.time.ZoneOffset.UTC, now))
        compose.setContent {
            RecordSelector(gallery(listOf(episode.saved), emptyMap()), nowPlaying = null, playing = false, enabled = true, onPlay = { _, _ -> }, onToggle = {}, onDetails = {})
        }
        compose.onNodeWithTag("record_headline").assertTextEquals("Google Expands AI Mode Ads + 2 more")
        compose.onNodeWithTag("record_subtitle").assertTextEquals(episodeDetails(episode))
        assertFalse(episodeDetails(episode).contains("Toolsa"))
    }

    @Test fun longPressAndOverflowMenusOfferSaveMp3AndShareOnReadyEpisodesOnly() {
        val actions = mutableListOf<Pair<String, String>>()
        val scrollState = androidx.compose.foundation.ScrollState(0)
        compose.setContent {
            RecordSelector(
                shelves,
                nowPlaying = null,
                playing = false,
                enabled = true,
                onPlay = { _, _ -> },
                onToggle = {},
                onDetails = {},
                modifier = androidx.compose.ui.Modifier.verticalScroll(scrollState),
                menu = { e, close ->
                    if (e.job.status == "ready") {
                        androidx.compose.material3.DropdownMenuItem({ androidx.compose.material3.Text("Save MP3") }, { actions += "save" to e.job.id; close() })
                        androidx.compose.material3.DropdownMenuItem({ androidx.compose.material3.Text("Share") }, { actions += "share" to e.job.id; close() })
                    }
                    androidx.compose.material3.DropdownMenuItem({ androidx.compose.material3.Text("Delete") }, { actions += "delete" to e.job.id; close() })
                },
            )
        }
        // Long-press on centred record opens its menu.
        compose.onNodeWithTag("record_n3").performTouchInput { longClick() }
        compose.onNodeWithText("Save MP3").performClick()
        assertEquals(listOf("save" to "n3"), actions)

        // Overflow button on Up next row opens that row's menu.
        compose.onNodeWithTag("record_next_menu_n2").performScrollTo().performClick()
        compose.onNodeWithText("Share").performClick()
        assertEquals(listOf("save" to "n3", "share" to "n2"), actions)

        // Long-press on Up next row also opens that row's menu.
        compose.onNodeWithTag("record_next_n2").performScrollTo().performTouchInput { longClick() }
        compose.onNodeWithText("Save MP3").performClick()
        assertEquals(listOf("save" to "n3", "share" to "n2", "save" to "n2"), actions)

        // Unfinished Up next row under All does not offer Save MP3 or Share.
        compose.runOnIdle { kotlinx.coroutines.runBlocking { scrollState.scrollTo(0) } }
        compose.onNodeWithTag("record_chip_all").performClick()
        compose.onNodeWithTag("record_next_menu_m5").performScrollTo().performClick()
        compose.onNodeWithText("Save MP3").assertDoesNotExist()
        compose.onNodeWithText("Share").assertDoesNotExist()
        compose.onNodeWithText("Delete").assertExists().performClick()
        assertEquals("delete" to "m5", actions.last())
    }

    private fun show(onPlay: (Episode, List<Episode>) -> Unit = { _, _ -> }, onDetails: (Episode) -> Unit = {}) = compose.setContent {
        RecordSelector(shelves, nowPlaying = null, playing = false, enabled = true, onPlay = onPlay, onToggle = {}, onDetails = onDetails,
            modifier = androidx.compose.ui.Modifier.verticalScroll(androidx.compose.foundation.rememberScrollState()))
    }
}
