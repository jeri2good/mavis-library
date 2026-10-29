package com.mavislibrary.app.data.reader

import com.mavislibrary.app.data.feeds.FeedSource
import com.mavislibrary.app.data.podsub.SiteRule
import com.mavislibrary.app.data.podcast.podcastJson
import kotlinx.serialization.encodeToString
import org.junit.Assert.*
import org.junit.Test

class SourceCompatibilityTest {
    private val old = """{"id":"s","name":"Site","feedUrl":"https://example.com/feed","siteUrl":"https://example.com","addedAt":1}"""
    @Test fun oldSourceDecodesAsFeed() { assertEquals("feed", podcastJson.decodeFromString<FeedSource>(old).source) }
    @Test fun oldRuleDecodesAsFeed() { assertEquals("feed", podcastJson.decodeFromString<SiteRule>(old).source) }
    @Test fun pageSurvivesRoundTrip() {
        val source = podcastJson.decodeFromString<FeedSource>(old).copy(source = "page")
        assertEquals(source, podcastJson.decodeFromString<FeedSource>(podcastJson.encodeToString(source)))
        val rule = podcastJson.decodeFromString<SiteRule>(old).copy(source = "page")
        assertEquals(rule, podcastJson.decodeFromString<SiteRule>(podcastJson.encodeToString(rule)))
    }
}
