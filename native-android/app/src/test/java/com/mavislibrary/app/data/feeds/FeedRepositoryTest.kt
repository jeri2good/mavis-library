package com.mavislibrary.app.data.feeds

import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)
@org.robolectric.annotation.Config(sdk = [35])
class FeedRepositoryTest {
    @Test fun cacheExpiryOfflineAndMerge() = runTest {
        var online = true; var now = 0L; var calls = 0; var fail = false
        val repository = FeedRepository(FeedFetcher { url ->
            calls++
            if (fail) error("offline")
            FeedResponse(url, 200, "<rss><channel><title>Feed</title><item><title>Post</title><link>https://example.com/shared</link></item></channel></rss>")
        }, { online }, { now })
        val a = FeedSource("a", "A", "https://example.com/a", "https://example.com/", 0)
        val b = a.copy(id = "b", name = "B", feedUrl = "https://example.com/b")
        assertEquals(1, repository.load(listOf(a, b)).stories.size)
        assertEquals(2, calls)
        repository.load(listOf(a)); assertEquals(2, calls)
        online = false
        assertTrue(repository.load(listOf(a)).note!!.contains("Offline"))
        assertEquals(2, calls)
        online = true; now = 900_001; fail = true
        assertEquals(1, repository.load(listOf(a)).stories.size)
        assertEquals(3, calls)
        assertTrue(repository.load(listOf(a)).note!!.contains("cached"))
        assertTrue(repository.load(listOf(a.copy(enabled = false))).stories.isEmpty())
    }
}
