package com.mavislibrary.app.data.feeds

import org.junit.Assert.*
import org.junit.Test

@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)
@org.robolectric.annotation.Config(sdk = [35])
class FeedParserTest {
    @Test fun realBeehiivFixture() {
        val feed = FeedParser.parse(javaClass.getResource("/feeds/world-of-ai.xml")!!.readText())
        assertEquals("World of AI", feed.title)
        assertEquals(3, feed.itemCount)
        assertEquals(3, feed.stories.size)
        assertNotNull(feed.newest)
        assertTrue(feed.stories.all { it.item.url.startsWith("https://intheworldofai.com/p/") && it.item.summary.length <= 200 })
    }
    @Test fun cdataEmojiEntitiesAndMissingDates() {
        val feed = FeedParser.parse("""<rss><channel><title>AI &amp; Tech</title><item><title><![CDATA[🤖 A & B]]></title><guid>abc</guid><link>https://example.com/a</link><description><![CDATA[<p>Hello &amp; goodbye</p>]]></description></item></channel></rss>""")
        assertEquals("🤖 A & B", feed.stories.single().item.title)
        assertEquals("Hello & goodbye", feed.stories.single().item.summary)
        assertNull(feed.newest)
    }
    @Test fun atomWithMediaNamespace() {
        val feed = FeedParser.parse("""<feed xmlns="http://www.w3.org/2005/Atom" xmlns:media="http://search.yahoo.com/mrss/"><title>YouTube</title><entry><id>yt:123</id><title>Video</title><link rel="self" href="https://example.com/self"/><link rel="alternate" href="https://www.youtube.com/watch?v=123"/><published>2026-10-09T12:00:00Z</published><media:group><media:title>Wrong</media:title></media:group></entry></feed>""")
        assertEquals("Video", feed.stories.single().item.title)
        assertEquals("https://www.youtube.com/watch?v=123", feed.stories.single().item.url)
        assertNotNull(feed.newest)
    }
    @Test fun rssRdfAndDates() {
        val feed = FeedParser.parse("""<rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#" xmlns="http://purl.org/rss/1.0/" xmlns:dc="http://purl.org/dc/elements/1.1/"><channel><title>RDF</title></channel><item><title>Post</title><link>https://example.com/post</link><dc:date>2026-10-09T12:00:00Z</dc:date></item></rdf:RDF>""")
        assertEquals("RDF", feed.title); assertEquals(1, feed.stories.size); assertNotNull(feed.newest)
    }
    @Test fun rejectsMalformedDtdAndOversized() {
        listOf("<rss><channel>", "<html></html>", "", "<!DOCTYPE rss [<!ENTITY bomb 'lol'>]><rss/>", "<!doctype rss><rss/>", "<rss>\u0000</rss>", " ".repeat(MAX_FEED_BYTES + 1)).forEach {
            assertTrue(runCatching { FeedParser.parse(it) }.isFailure)
        }
    }
    @Test fun capsAndSortsDroppingHttpAndDuplicateIds() {
        val items = (1..100).joinToString("") { "<item><title>Post $it</title><guid>$it</guid><link>https://example.com/$it</link><pubDate>Fri, 09 Oct 2026 12:00:${(it % 60).toString().padStart(2, '0')} +0000</pubDate></item>" }
        val feed = FeedParser.parse("<rss><channel><title>Test</title>$items<item><title>Bad</title><link>http://example.com/bad</link></item></channel></rss>")
        assertEquals(101, feed.itemCount); assertEquals(40, feed.stories.size)
        assertTrue(feed.stories.zipWithNext().all { (a, b) -> a.published!! >= b.published!! })
    }

    @Test fun numericEntitiesDecodeToEmojiAndAmpersandIsLast() {
        assertEquals("Claude Builds Live Dashboards \uD83D\uDCCA, GPT-6.1 Sol Goes Ultrafast \u26A1",
            FeedParser.decodeEntities("Claude Builds Live Dashboards &#128202;, GPT-6.1 Sol Goes Ultrafast &#x26A1;"))
        assertEquals("Q&A &lt;b&gt;", FeedParser.decodeEntities("Q&amp;A &amp;lt;b&amp;gt;"))
        assertEquals("ab", FeedParser.decodeEntities("a&#0;b"))
        assertEquals("ab", FeedParser.decodeEntities("a&#xD800;b"))
        assertEquals("ab", FeedParser.decodeEntities("a&#1114112;b"))
    }
}
