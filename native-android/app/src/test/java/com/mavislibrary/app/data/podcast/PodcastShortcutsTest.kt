package com.mavislibrary.app.data.podcast

import org.junit.Assert.*
import org.junit.Test

class PodcastShortcutsTest {
    @Test fun chapterUsesFullSourceAndNeverWholeBookId() {
        val text = "Opening paragraph\n\n" + "Later page. ".repeat(5000)
        val draft = PodcastDraft.chapter("My Book", "Chapter II", text)
        val request = draft.request("debate", "long", "local")
        assertEquals("chapter", request.kind)
        assertEquals("My Book - Chapter II", request.title)
        assertEquals(text, request.text)
        assertNull(request.gutenbergId)
        assertEquals("debate", request.style)
        assertEquals("long", request.length)
        assertFalse(draft.confirmShare)
    }

    @Test fun chapterCapsGatewayTextAndKeepsOriginalCount() {
        val draft = PodcastDraft.chapter("Book", "Section 3", "x".repeat(400_123))
        assertEquals(MAX_PODCAST_TEXT, draft.text.length)
        assertEquals(400_123, draft.originalChars)
        assertEquals("Book - Section 3", draft.title)
    }

    @Test fun articlePrefillsUrlAndTitleWithoutExtraConfirmation() {
        val draft = PodcastDraft.article("AI news", "https://example.org/story")
        val request = draft.request("critique", "short", "auto")
        assertEquals("article", request.kind)
        assertEquals("AI news", request.title)
        assertEquals("https://example.org/story", request.url)
        assertNull(request.text)
        assertFalse(draft.confirmShare)
        assertEquals("critique", request.style)
        assertEquals("short", request.length)
    }

    @Test fun sharedArticleWithSurroundingTextKeepsTitleAndIsOneTapReview() {
        val shared = routePodcastShare("An interesting article\nhttps://example.org/story") as PodcastShare.Lounge
        assertEquals("article", shared.draft.kind)
        assertEquals("An interesting article", shared.draft.title)
        assertEquals("https://example.org/story", shared.draft.url)
        assertFalse(shared.draft.confirmShare)
    }

    @Test fun kidsAndUnloadedModeNeverAllowShortcuts() {
        assertFalse(loungeRouteAllowed(true))
        assertFalse(loungeRouteAllowed(null))
        assertTrue(loungeRouteAllowed(false))
    }
}
