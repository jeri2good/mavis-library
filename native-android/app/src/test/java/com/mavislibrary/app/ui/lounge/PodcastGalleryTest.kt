package com.mavislibrary.app.ui.lounge

import com.mavislibrary.app.data.podcast.PodcastJob
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
        assertEquals("Oct 20 · 14 min · Deep dive · Downloaded", episodeDetails(Episode(saved("x", local = "/f.mp3"), null), utc))
        assertEquals("Oct 20 · 12 min left · Deep dive", episodeDetails(Episode(saved("x"), 120_000), utc))
        assertEquals(0.5f, Episode(saved("x"), 420_000).heard!!, 0.001f)
        assertEquals("45 sec", minutesLabel(45))
    }
}
