package com.mavislibrary.app.data.feeds

import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)
@org.robolectric.annotation.Config(sdk = [35])
class FeedDiscoveryTest {
    private val xml = "<rss><channel><title>World of AI</title><item><title>AI</title><link>https://intheworldofai.com/p/ai</link></item></channel></rss>"
    private fun discovery(responses: Map<String, String>) = FeedDiscovery { url -> FeedResponse(url, if (url in responses) 200 else 404, responses[url].orEmpty()) }
    @Test fun directFeed() = runTest {
        assertTrue(discovery(mapOf("https://example.com/" to xml)).discover("example.com") is DiscoveryResult.Found)
    }
    @Test fun alternateTag() = runTest {
        val d = discovery(mapOf("https://example.com/" to "<link type='application/rss+xml' href='/posts' rel='alternate'>", "https://example.com/posts" to xml))
        assertEquals("https://example.com/posts", (d.discover("example.com") as DiscoveryResult.Found).feedUrl)
    }
    @Test fun worldOfAiPageScanWithoutAlternate() = runTest {
        val url = "https://rss.beehiiv.com/ai_podcasts/5F2wtaPWzH.xml"
        val d = discovery(mapOf("https://intheworldofai.com/" to "<script>var feed = \"$url\";</script>", url to xml))
        assertEquals(url, (d.discover("intheworldofai.com") as DiscoveryResult.Found).feedUrl)
    }
    @Test fun commonPathFallback() = runTest {
        assertEquals("https://example.com/rss.xml", (discovery(mapOf("https://example.com/" to "<html/>", "https://example.com/rss.xml" to xml)).discover("example.com") as DiscoveryResult.Found).feedUrl)
    }
    @Test fun cloudflareAndNoFeedAndMalformed() = runTest {
        val blocked = FeedDiscovery { FeedResponse(it, 403, "Just a moment...") }.discover("example.com") as DiscoveryResult.NotFound
        assertEquals(DiscoveryResult.Reason.BLOCKED, blocked.reason); assertTrue(blocked.message.contains("blocks apps"))
        assertEquals(DiscoveryResult.Reason.NO_FEED, (discovery(mapOf("https://example.com/" to "<html/>")).discover("example.com") as DiscoveryResult.NotFound).reason)
        assertEquals(DiscoveryResult.Reason.NOT_A_FEED, (discovery(mapOf("https://example.com/" to "<rss>")).discover("example.com") as DiscoveryResult.NotFound).reason)
    }
    @Test fun refusesRedirectToPrivate() = runTest {
        val d = FeedDiscovery { from -> FeedResponse(UrlPolicy.redirect(from, "https://127.0.0.1/feed"), 200, xml) }
        assertEquals(DiscoveryResult.Reason.UNREACHABLE, (d.discover("example.com") as DiscoveryResult.NotFound).reason)
    }
    @Test fun pageScanIsCappedAndRanked() {
        val html = (1..30).joinToString(" ") { "<a href='/feed/$it'>feed</a>" } + "<a href='https://rss.beehiiv.com/posts.xml'>RSS</a>"
        val candidates = discovery(emptyMap()).candidates("https://example.com/", html)
        assertEquals("https://rss.beehiiv.com/posts.xml", candidates.first())
        assertEquals(14, candidates.size)
    }
}
