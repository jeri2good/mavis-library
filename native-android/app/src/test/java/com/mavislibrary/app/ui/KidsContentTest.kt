package com.mavislibrary.app.ui.kids

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KidsContentTest {
    @Test fun onlySelectedBookAndItsReaderCanOpen() {
        val route = "#/book/gutenberg%3A11"
        assertTrue(kidsWebRouteAllowed("https://localhost/#/book/gutenberg:11", route))
        assertTrue(kidsWebRouteAllowed("https://localhost/#/read/gutenberg%3A11", route))
        listOf("https://localhost/#/read/gutenberg:55", "https://localhost/#/settings",
            "https://localhost/#/book/gutenberg:999", "https://localhost/#/book/gutenberg:11?owner=1",
            "https://localhost.evil/#/book/gutenberg:11", "http://localhost/#/book/gutenberg:11",
            "https://localhost/elsewhere#/book/gutenberg:11", "file:///android_asset/index.html#/book/gutenberg:11"
        ).forEach { assertFalse(it, kidsWebRouteAllowed(it, route)) }
        assertFalse(kidsWebRouteAllowed("https://localhost/#/book/gutenberg:999", "#/book/gutenberg:999"))
    }

    @Test fun bibleStoriesMatchTwelveWebRoutesAndCannotLeaveTheirPassage() {
        assertEquals(12, BIBLE_STORIES.size)
        assertEquals("#/bible/Gen/1", BIBLE_STORIES.first().route)
        assertEquals("#/bible/Luke/10?v=25", BIBLE_STORIES[8].route)
        assertEquals("#/bible/Luke/19", BIBLE_STORIES.last().route)
        val selected = BIBLE_STORIES[8].route
        assertTrue(kidsWebRouteAllowed("https://localhost/$selected", selected))
        assertFalse(kidsWebRouteAllowed("https://localhost/#/bible/Luke/11", selected))
        assertFalse(kidsWebRouteAllowed("https://localhost/#/bible/Luke/10?v=26", selected))
        assertFalse(kidsWebRouteAllowed("https://localhost/#/settings", selected))
    }
}
