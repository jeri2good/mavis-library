package com.mavislibrary.app.ui.lounge

import com.mavislibrary.app.data.model.BookSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PodcastWebBooksTest {
    @Test fun parsesReaderBooksWithProgress() {
        val rows = parseWebBooks("""[{"key":"gutenberg:2701","source":"gutenberg","sourceId":"2701","title":"Moby Dick","authors":["Herman Melville"],"coverUrl":"https://x/c.jpg","percent":0.42},
            {"key":"openlibrary:OL1W","source":"openlibrary","title":"Other","authors":[],"coverUrl":null,"percent":null}]""")
        assertEquals(2, rows.size)
        val (moby, pct) = rows[0]
        assertEquals("Moby Dick", moby.title); assertEquals(BookSource.GUTENBERG, moby.source); assertEquals("2701", moby.sourceId)
        assertEquals("Herman Melville", moby.authorLine); assertEquals(0.42f, pct!!, 0.001f)
        assertEquals(BookSource.OPEN_LIBRARY, rows[1].first.source); assertNull(rows[1].first.coverUrl); assertNull(rows[1].second)
    }

    @Test fun malformedInputIsEmptyNotACrash() {
        assertTrue(parseWebBooks(null).isEmpty())
        assertTrue(parseWebBooks("not json").isEmpty())
        assertEquals(1, parseWebBooks("""[{"title":"no key"}, 5, {"key":"gutenberg:1"}]""").size)
    }
}
