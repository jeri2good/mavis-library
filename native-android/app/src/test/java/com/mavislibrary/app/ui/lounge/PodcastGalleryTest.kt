package com.mavislibrary.app.ui.lounge

import com.mavislibrary.app.data.podcast.PodcastJob
import com.mavislibrary.app.data.podcast.PodcastProblem
import com.mavislibrary.app.data.podcast.PodcastProblemKind
import com.mavislibrary.app.data.podcast.PodcastSyncState
import com.mavislibrary.app.data.podcast.SavedPodcast
import org.junit.Assert.*
import org.junit.Test
import java.time.ZoneId

class PodcastGalleryTest {
    private fun saved(id: String, status: String = "ready", duration: Int? = 840, progress: Double = 0.0, local: String? = null) =
        SavedPodcast(PodcastJob(id, status, progress, title = "Episode $id", kind = "chapter", style = "deep-dive", durationSec = duration,
            createdAt = "2026-10-20T06:31:00Z"), "studio", localFile = local)

    @Test fun episodesSortIntoSectionsByStatusAndListening() {
        val list = listOf(saved("new"), saved("started"), saved("done"), saved("making", "writing"), saved("broken", "failed"), saved("unknown", duration = null))
        val positions = mapOf("podcast:studio/started" to 120_000L, "podcast:studio/done" to 830_000L, "podcast:studio/unknown" to 600_000L, "podcast:studio/new" to 2_000L)
        val shelves = gallery(list, positions)
        assertEquals(listOf(PodcastShelf.CONTINUE, PodcastShelf.NEW, PodcastShelf.MAKING, PodcastShelf.FAILED, PodcastShelf.PLAYED), shelves.keys.toList())
        assertEquals(listOf("started", "unknown"), shelves.getValue(PodcastShelf.CONTINUE).map { it.job.id })
        assertEquals(listOf("new"), shelves.getValue(PodcastShelf.NEW).map { it.job.id })
        assertEquals(listOf("done"), shelves.getValue(PodcastShelf.PLAYED).map { it.job.id })
        assertTrue(gallery(emptyList(), emptyMap()).isEmpty())
    }

    @Test fun upNextPlaysTheNewEpisodesBelow() {
        val shelves = gallery(listOf(saved("a"), saved("b"), saved("c"), saved("old")), mapOf("podcast:studio/old" to 900_000L))
        val new = shelves.getValue(PodcastShelf.NEW)
        assertEquals(listOf("b", "c"), upNext(new[1], shelves).map { it.job.id })
        assertEquals(listOf("old", "a", "b", "c"), upNext(shelves.getValue(PodcastShelf.PLAYED).single(), shelves).map { it.job.id })
    }

    @Test fun plainLanguageStatusAndDetails() {
        assertEquals("Writing the script · 40%", stageLabel(saved("x", "writing", progress = 40.0).job))
        assertEquals("Waiting its turn", stageLabel(saved("x", "queued").job))
        assertEquals("Ready", stageLabel(saved("x").job))
        val utc = ZoneId.of("UTC")
        val now = java.time.Instant.parse("2026-10-29T12:00:00Z")
        assertEquals("Tue, Oct 20 · 14 min · Deep dive · Downloaded", episodeDetails(Episode(saved("x", local = "/f.mp3"), null), utc, now))
        assertEquals("Tue, Oct 20 · 12 min left · Deep dive", episodeDetails(Episode(saved("x"), 120_000), utc, now))
        val briefing = saved("b").let { it.copy(job = it.job.copy(createdAt = "2026-10-29T06:44:00Z", sites = listOf("Future Tools", "IGN"))) }
        assertEquals("Today 6:44 AM · Future Tools · IGN · 14 min · Deep dive", episodeDetails(Episode(briefing, null), utc, now))
        assertEquals(0.5f, Episode(saved("x"), 420_000).heard!!, 0.001f)
        assertEquals("45 sec", minutesLabel(45))
    }

    @Test fun firstLoadRefreshAndEachFailureReadDifferently() {
        val first = loungeBanner(PodcastSyncState(), hasEpisodes = false, waking = false)
        assertEquals("Loading your podcasts…", first.text); assertTrue(first.skeleton); assertTrue(first.busy); assertFalse(first.canCreate)
        val firstWithCache = loungeBanner(PodcastSyncState(), hasEpisodes = true, waking = false)
        assertFalse(firstWithCache.skeleton); assertTrue(firstWithCache.busy)
        val connected = loungeBanner(PodcastSyncState(loading = false, synced = true, lastSyncMs = 1), true, false)
        assertEquals("Studio connected", connected.text); assertFalse(connected.busy); assertFalse(connected.error); assertTrue(connected.canCreate)
        val refreshing = loungeBanner(PodcastSyncState(loading = true, synced = true), true, false)
        assertEquals("Checking for new podcasts…", refreshing.text); assertTrue(refreshing.busy); assertFalse(refreshing.skeleton); assertTrue(refreshing.canCreate)
        val offline = loungeBanner(PodcastSyncState(false, false, PodcastProblem(PodcastProblemKind.OFFLINE, "Can't reach Mavis. Check your connection.")), true, false)
        assertEquals("You're offline", offline.text); assertTrue(offline.error); assertFalse(offline.skeleton)
        assertTrue(offline.detail!!.contains("Downloaded podcasts still play.")); assertTrue(offline.detail!!.contains("keeps trying"))
        assertTrue(offline.canCreate); assertNull(offline.settingsLabel)
        val retrying = loungeBanner(PodcastSyncState(true, false, PodcastProblem(PodcastProblemKind.STUDIO_DOWN, "Studio unavailable, try later")), false, false)
        assertEquals("Studio not answering · trying again…", retrying.text); assertTrue(retrying.busy); assertFalse(retrying.error); assertFalse(retrying.skeleton)
        val signIn = loungeBanner(PodcastSyncState(false, false, PodcastProblem(PodcastProblemKind.SIGN_IN, "Sign in to use Podcast Lounge")), false, false)
        assertEquals("Sign in in Settings", signIn.settingsLabel); assertFalse(signIn.canCreate); assertNull(signIn.detail)
        val access = loungeBanner(PodcastSyncState(false, false, PodcastProblem(PodcastProblemKind.ACCESS, "Your account doesn't have podcast access yet.")), true, false)
        assertEquals("Request access", access.settingsLabel); assertFalse(access.canCreate)
        assertFalse(loungeBanner(PodcastSyncState(false, false, PodcastProblem(PodcastProblemKind.NOT_CONFIGURED, "x")), false, false).canCreate)
        val waking = loungeBanner(PodcastSyncState(), false, true)
        assertEquals("Waking up the studio…", waking.text); assertTrue(waking.skeleton)
        assertEquals("Checking less often for a minute", trackingNote(PodcastProblem(PodcastProblemKind.SLOW_DOWN, "x")))
        assertTrue(trackingNote(PodcastProblem(PodcastProblemKind.OFFLINE, "x")).contains("offline"))
    }
}
