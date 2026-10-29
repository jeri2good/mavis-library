package com.mavislibrary.app.data.reader

import com.mavislibrary.app.data.feeds.*
import com.mavislibrary.app.data.podcast.PodcastApi
import kotlinx.coroutines.test.runTest
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test

class PageTest {
    @Test fun pageSitesUseAuthenticatedGatewayAndCache() = runTest {
        var calls = 0
        val api = ReaderApi(PodcastApi("https://gateway.example", execute = { req ->
            calls++
            assertEquals("Bearer fixture", req.header("Authorization"))
            assertEquals("/api/podsub/page", req.url.encodedPath)
            assertEquals("https://example.com/news", req.url.queryParameter("url"))
            Response.Builder().request(req).protocol(Protocol.HTTP_1_1).code(200).message("fixture")
                .body("""{"posts":[{"id":"a","title":"Article","link":"https://example.com/a","publishedAt":123}]}""".toResponseBody()).build()
        }, token = { "fixture" }))
        var online = true
        val repo = FeedRepository(FeedFetcher { error("Page must not use feed parser") }, { online }, reader = api)
        val site = FeedSource("s", "Site", "https://example.com/news", "https://example.com", 0, source = "page")
        val first = repo.load(listOf(site))
        assertEquals("a", first.stories.single().item.id)
        assertEquals("Site", first.stories.single().item.source)
        online = false
        assertEquals(first.stories, repo.load(listOf(site)).stories)
        assertEquals(1, calls)
    }
}
