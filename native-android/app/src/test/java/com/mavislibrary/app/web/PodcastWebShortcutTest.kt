package com.mavislibrary.app.web

import com.mavislibrary.app.data.podcast.MAX_PODCAST_TEXT
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PodcastWebShortcutTest {
    @Test fun chapterBridgePrefillsTrimmedSourceAndOriginalCount() {
        val payload = JSONObject().put("kind", "chapter").put("book", "Gutenberg Book")
            .put("chapter", "Chapter V").put("text", "x".repeat(400_000)).put("originalChars", 410_000)
        val shortcut = PodcastWebShortcut.parse(payload.toString())
        assertEquals("Gutenberg Book - Chapter V", shortcut.draft.title)
        assertEquals(MAX_PODCAST_TEXT, shortcut.draft.text.length)
        assertEquals(410_000, shortcut.draft.originalChars)
        assertNull(shortcut.draft.gutenbergId)
        assertFalse(shortcut.openArticle)
    }

    @Test fun articleCanOpenReaderOrLoungeWithIdenticalSource() {
        val payload = JSONObject().put("kind", "article").put("title", "AI article").put("url", "https://example.org/news")
        val lounge = PodcastWebShortcut.parse(payload.toString())
        val reader = PodcastWebShortcut.parse(payload.put("action", "openArticle").toString())
        assertEquals(lounge.draft, reader.draft)
        assertFalse(lounge.openArticle)
        assertTrue(reader.openArticle)
        assertEquals("article", reader.draft.kind)
    }

    @Test fun wholeBookIsSeparateExplicitPayload() {
        val shortcut = PodcastWebShortcut.parse("""{"kind":"book","title":"Pride and Prejudice","gutenbergId":1342}""")
        assertEquals("book", shortcut.draft.kind)
        assertEquals(1342, shortcut.draft.gutenbergId)
        assertTrue(shortcut.draft.text.isBlank())
    }

    @Test fun rejectsEmptyChaptersBadIdsCredentialsAndUnsupportedKinds() {
        listOf(
            """{"kind":"chapter","book":"Book","chapter":"1","text":""}""",
            """{"kind":"article","url":"https://user:pass@example.org/"}""",
            """{"kind":"article","url":"javascript:alert(1)"}""",
            """{"kind":"book","title":"Book","gutenbergId":0}""",
            """{"kind":"topic","title":"Unrequested"}""",
        ).forEach { assertTrue(it, runCatching { PodcastWebShortcut.parse(it) }.isFailure) }
    }
}
