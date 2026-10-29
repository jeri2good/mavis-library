package com.mavislibrary.app.data.reader

import com.mavislibrary.app.data.feeds.*
import com.mavislibrary.app.data.podcast.PodcastApi
import kotlinx.coroutines.test.runTest
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test

@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)
@org.robolectric.annotation.Config(sdk = [35])
class PreviewTest {
    private fun api(body: String, code: Int = 200) = ReaderApi(PodcastApi("https://gateway.example", execute = { req ->
        assertEquals("owner-test", req.header("x-mavis-access"))
        assertEquals("/api/podsub/preview", req.url.encodedPath)
        Response.Builder().request(req).protocol(Protocol.HTTP_1_1).code(code).message("fixture")
            .body(body.toResponseBody()).build()
    }, owner = { "owner-test" }, token = { null }))
    private fun discovery(body: String, code: Int = 200) = FeedDiscovery(FeedFetcher { FeedResponse(it, code, body) })
    @Test fun localFeedSkipsPreview() = runTest {
        val reader = object : ReaderService { override suspend fun preview(url: String): SitePreview = error("must not call") }
        val result = discoverSite("https://example.com", discovery("<rss><channel><title>Feed</title><item><title>One</title><link>https://example.com/one</link></item></channel></rss>"), reader)
        assertTrue(result.result is DiscoveryResult.Found); assertNull(result.preview)
    }
    @Test fun pageFallbackUsesGatewayAndReturnsHeadlines() = runTest {
        val json = """{"source":"page","feedUrl":"https://example.com","siteUrl":"https://example.com","title":"Site","posts":[{"title":"One","link":"https://example.com/1"},{"title":"Two","link":"https://example.com/2"},{"title":"Three","link":"https://example.com/3"}]}"""
        val result = discoverSite("https://example.com", discovery("blocked", 403), api(json))
        assertEquals("page", result.preview?.source); assertEquals(3, (result.result as DiscoveryResult.Found).items)
    }
    @Test fun noArticlesStaysNotFound() = runTest {
        val result = discoverSite("https://example.com", discovery("<html></html>"), api("""{"code":"no_articles"}""", 422))
        assertTrue(result.result is DiscoveryResult.NotFound); assertNull(result.preview)
    }
}
