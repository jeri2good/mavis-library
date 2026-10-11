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

    @Test fun mainDocumentQueryIsCurrentlyNotPartOfTheAllowListCheck() {
        assertTrue(kidsWebRouteAllowed("https://localhost/?extra=1#/book/gutenberg:11", book))
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
