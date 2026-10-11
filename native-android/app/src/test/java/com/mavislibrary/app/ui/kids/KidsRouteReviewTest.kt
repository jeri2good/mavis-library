package com.mavislibrary.app.ui.kids

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KidsRouteReviewTest {
    private val book = "#/book/gutenberg%3A11"

    @Test fun encodedCuratedBookAndReaderAreAllowed() {
        assertTrue(kidsWebRouteAllowed("https://localhost/#/book/gutenberg%3A11", book))
        assertTrue(kidsWebRouteAllowed("https://localhost/#/read/gutenberg%3a11", book))
        assertTrue(kidsWebRouteAllowed("https://localhost/#/read/gutenberg:11", book))
    }

    @Test fun authoritySchemePathAndFragmentTricksDoNotEscape() {
        listOf(
            "HTTPS://localhost/#/book/gutenberg:11",
            "https://LOCALHOST/#/book/gutenberg:11",
            "https://localhost@evil.example/#/book/gutenberg:11",
            "https://evil.example@localhost/#/book/gutenberg:11",
            "https://user%40name@localhost/#/book/gutenberg:11",
            "https://localhost:443/#/book/gutenberg:11",
            "https://localhost./#/book/gutenberg:11",
            "https://localhost/%2f#/book/gutenberg:11",
            "https://localhost/elsewhere#/book/gutenberg:11",
            "https://localhost/#/book/GUTENBERG:11",
            "https://localhost/#/book/gutenberg%253A11",
            "https://localhost/#/book/gutenberg:11%3Fowner=1",
            "https://localhost/#/read/gutenberg:55",
            "https://localhost/#/settings",
            "https://localhost/#/listen",
            "https://localhost/#/book/gutenberg%ZZ11",
        ).forEach { assertFalse(it, kidsWebRouteAllowed(it, book)) }
    }

    @Test fun mainDocumentQueryAndUserInfoAreRejected() {
        listOf("?extra=1", "?", "?x=%23%2Fsettings").forEach {
            assertFalse(kidsWebRouteAllowed("https://localhost/$it#/book/gutenberg:11", book))
        }
        assertFalse(kidsWebRouteAllowed("https://user@localhost/#/book/gutenberg:11", book))
    }

    @Test fun allFramesRejectOffsiteAndActiveSchemes() {
        val selected = "https://localhost/#/read/gutenberg:11"
        assertTrue(kidsNavigationAllowed(selected, book, true))
        assertTrue(kidsNavigationAllowed(selected, book, false))
        listOf("about:blank", "blob:https://localhost/abc", "data:text/html,chapter").forEach {
            assertTrue(it, kidsNavigationAllowed(it, book, false))
            assertFalse(it, kidsNavigationAllowed(it, book, true))
        }
        listOf("https://elsewhere.test/", "https://localhost/#/settings", "https://localhost/?q=1#/read/gutenberg:11",
            "http://localhost/#/read/gutenberg:11", "intent://open", "market://details", "tel:123", "mailto:a@b.test",
            "sms:123", "geo:0,0", "javascript:alert(1)", "file:///etc/passwd", "about:config",
            "blob:https://evil.test/id", "datax:text/html,chapter"
        ).forEach { url ->
            assertFalse(url, kidsNavigationAllowed(url, book, false))
            assertFalse(url, kidsNavigationAllowed(url, book, true))
        }
    }

    @Test fun uncuratedSelectedRoutesAndBiblePassageChangesAreRejected() {
        assertFalse(kidsWebRouteAllowed("https://localhost/#/book/gutenberg:999", "#/book/gutenberg:999"))
        assertFalse(kidsWebRouteAllowed("https://localhost/#/book/openlibrary:11", "#/book/openlibrary:11"))
        val bible = "#/bible/Luke/10?v=25"
        assertTrue(kidsWebRouteAllowed("https://localhost/$bible", bible))
        listOf(
            "https://localhost/#/bible/Luke/10?v=26",
            "https://localhost/#/bible/Luke/10?v=25&ve=26",
            "https://localhost/#/bible/luke/10?v=25",
            "https://localhost/#/bible/search",
            "https://localhost/#/bible/Luke/11",
        ).forEach { assertFalse(it, kidsWebRouteAllowed(it, bible)) }
    }
}
