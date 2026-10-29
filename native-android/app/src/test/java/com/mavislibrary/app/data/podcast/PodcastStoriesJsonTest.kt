package com.mavislibrary.app.data.podcast

import kotlinx.serialization.encodeToString
import org.junit.Assert.*
import org.junit.Test

class PodcastStoriesJsonTest {
    @Test fun briefingJobDecodesSitesAndStories() {
        val job = podcastJson.decodeFromString<PodcastJob>("""{"id":"b1","status":"ready","title":"Thu, Oct 29 · Google Expands AI Mode Ads + 1 more",
            "kind":"chapter","style":"deep-dive","createdAt":"2026-10-29T10:44:00.000Z","sites":["Future Tools","IGN"],
            "stories":[{"title":"Google Expands AI Mode Ads","site":"Future Tools","link":"https://example.org/a"},{"title":"Zelda","site":"IGN"},{"title":"Bare"}]}""")
        assertEquals(listOf("Future Tools", "IGN"), job.sites)
        assertEquals(PodcastStory("Google Expands AI Mode Ads", "Future Tools", "https://example.org/a"), job.stories[0])
        assertNull(job.stories[1].link)
        assertEquals(PodcastStory("Bare"), job.stories[2])
    }

    @Test fun oldJobAndSavedPodcastStillDecode() {
        val job = podcastJson.decodeFromString<PodcastJob>("""{"id":"o1","status":"ready","title":"Daily briefing","kind":"chapter","style":"deep-dive","createdAt":"2026-10-20T06:31:00Z"}""")
        assertTrue(job.sites.isEmpty()); assertTrue(job.stories.isEmpty())
        val saved = podcastJson.decodeFromString<List<SavedPodcast>>("""[{"job":{"id":"o1","status":"ready","title":"Daily briefing","kind":"chapter","style":"deep-dive"},"studio":"https://s.example.org"}]""")
        assertTrue(saved.single().job.stories.isEmpty())
        val round = podcastJson.decodeFromString<PodcastJob>(podcastJson.encodeToString(job.copy(stories = listOf(PodcastStory("A", "IGN")))))
        assertEquals(listOf(PodcastStory("A", "IGN")), round.stories)
    }
}
