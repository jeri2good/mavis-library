package com.mavislibrary.app.ui.shelf

import com.mavislibrary.app.data.feeds.FeedSource
import com.mavislibrary.app.data.feeds.FeedStory
import com.mavislibrary.app.data.api.TechItem
import com.mavislibrary.app.data.model.Book
import com.mavislibrary.app.data.model.BookSource
import com.mavislibrary.app.data.stats.ReadingDay
import com.mavislibrary.app.data.stats.ReadingStats
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class ShelfRowsTest {
    @Test fun circularIndexWrapsInBothDirections() {
        assertEquals(0, CircularRow.realIndex(0, 3))
        assertEquals(2, CircularRow.realIndex(5, 3))
        assertEquals(2, CircularRow.realIndex(-1, 3))
        assertEquals(0, CircularRow.realIndex(-3, 3))
        assertEquals(1, CircularRow.realIndex(-5, 3))
        val start = CircularRow.startIndex(7, loops = true)
        assertEquals("starts on the first book", 0, CircularRow.realIndex(start, 7))
        assertEquals("one step left is the last book", 6, CircularRow.realIndex(start - 1, 7))
        assertEquals("one step past the end is the first again", 0, CircularRow.realIndex(start + 7, 7))
        assertTrue("room to scroll a long way both ways", start > 1_000_000 && CircularRow.VIRTUAL_COUNT - start > 1_000_000)
    }

    @Test fun rowsThatFitDontLoop() {
        // 360px wide, 92px books with 10px gaps: three fit ((360 + 10) / 102 = 3).
        val fits = CircularRow.fits(360f, 92f, 10f)
        assertEquals(3, fits)
        listOf(1, 2, 3).forEach { n ->
            assertFalse(CircularRow.loops(n, fits))
            assertEquals(n, CircularRow.itemCount(n, CircularRow.loops(n, fits)))
            assertEquals(0, CircularRow.startIndex(n, CircularRow.loops(n, fits)))
        }
        assertTrue(CircularRow.loops(4, fits))
        assertEquals(CircularRow.VIRTUAL_COUNT, CircularRow.itemCount(4, true))
        assertEquals(0, CircularRow.itemCount(0, true))
        assertEquals(0, CircularRow.startIndex(0, true))
    }

    @Test fun loopKeysStayUniqueWhenBooksRepeat() {
        val keys = listOf("a", "b", "c")
        val start = CircularRow.startIndex(3, true)
        val shown = (start - 6 until start + 9).map { CircularRow.key(it, keys[CircularRow.realIndex(it, 3)]) }
        assertEquals(shown.size, shown.toSet().size)
        assertTrue(shown.all { it.endsWith("|a") || it.endsWith("|b") || it.endsWith("|c") })
    }

    @Test fun rowOrderIsMineContinuePopularSites() {
        val state = ShelfUiState(
            books = listOf(book(1), book(2), book(3)), loading = false,
            activity = mapOf("gutenberg:3" to 300L, "gutenberg:1" to 100L, "gutenberg:99" to 999L),
            popular = listOf(PopularShelf("popular", "Most popular", ""), PopularShelf("kids", "Children's books", "", emptyList())),
            siteSources = listOf(site("s1", "Future Tools")),
        )
        val rows = shelfRows(state)
        assertEquals(listOf("mine", "continue", "popular", "kids", "sites"), rows.map { it.id })
        assertEquals(listOf("My books", "Continue reading", "Most popular", "Children's books", "Sites"), rows.map { it.label })
        assertEquals("most recent first, only saved books", listOf(book(3), book(1)), (rows[1] as ShelfRow.Continue).books)
    }

    @Test fun emptyStatesSkipContinueAndSitesButKeepMyBooks() {
        val rows = shelfRows(ShelfUiState(loading = false))
        assertEquals(listOf("mine"), rows.map { it.id })
        val mine = rows.single() as ShelfRow.Mine
        assertTrue(mine.books.isEmpty()); assertFalse(mine.loading)
        assertTrue((shelfRows(ShelfUiState()).single() as ShelfRow.Mine).loading)
        assertEquals("saved books with no progress get no Continue row", listOf("mine"),
            shelfRows(ShelfUiState(books = listOf(book(1)), loading = false)).map { it.id })
    }

    @Test fun readingActivityUsesReaderDaysAndAudiobookPositions() {
        val zone = ZoneId.of("UTC")
        val stats = ReadingStats(mapOf(
            LocalDate.parse("2026-10-27") to ReadingDay(120_000, setOf("book:gutenberg:1", "bible:john")),
            LocalDate.parse("2026-10-28") to ReadingDay(60_000, setOf("book:gutenberg:1")),
            LocalDate.parse("2026-10-29") to ReadingDay(0, setOf("book:gutenberg:2")),
        ))
        val day28 = LocalDate.parse("2026-10-28").atStartOfDay(zone).toInstant().toEpochMilli()
        val activity = readingActivity(stats, mapOf("gutenberg:3" to day28 + 5_000, "gutenberg:1" to 10L), zone)
        assertEquals(mapOf("gutenberg:1" to day28, "gutenberg:3" to day28 + 5_000), activity)
    }

    @Test fun siteBooksCountUnreadPerSite() {
        val posts = mapOf("s1" to listOf(story("a"), story("b"), story("c")))
        val sites = siteBooks(listOf(site("s1", "Future Tools"), site("s2", "IGN")), posts, mapOf("s1" to setOf("a")))
        assertEquals(listOf(SiteBook("s1", "Future Tools", "https://s1.example", 2), SiteBook("s2", "IGN", "https://s2.example", 0)), sites)
        assertEquals("https://s1.example/favicon.ico", faviconUrl("https://s1.example/blog"))
        assertNull(faviconUrl("http://insecure.example"))
    }

    @Test fun coverColourAndHeightAreStablePerKey() {
        assertEquals(hueOf("gutenberg:1"), hueOf("gutenberg:1"), 0f)
        assertTrue(hueOf("gutenberg:1") in 0f..359f)
        assertEquals(bookHeight("x"), bookHeight("x"))
        (1..50).forEach { assertTrue(bookHeight("gutenberg:$it").value in 128f..148f) }
    }

    private fun story(id: String) = FeedStory(TechItem(id = id, title = id, url = "https://s1.example/$id", source = "s1"), 1L)
    private fun site(id: String, name: String) = FeedSource(id, name, "https://$id.example/feed", "https://$id.example", 0L)
    private fun book(id: Int) = Book("gutenberg:$id", BookSource.GUTENBERG, "$id", "Book $id", listOf("Author"), null, emptyList(), null, listOf("en"), null, null)
}
