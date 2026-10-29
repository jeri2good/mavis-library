package com.mavislibrary.app.ui.shelf

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.mavislibrary.app.data.feeds.FeedSource
import com.mavislibrary.app.data.model.Book
import com.mavislibrary.app.data.model.BookSource
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ShelfScreenTest {
    @get:Rule val compose = createComposeRule()

    private val opened = mutableListOf<Book>()
    private val removed = mutableListOf<Book>()
    private val podcasts = mutableListOf<Book>()
    private val sites = mutableListOf<String>()
    private var webShelf = 0
    private var scans = 0
    private var retries = 0

    private fun show(state: ShelfUiState) = compose.setContent {
        ShelfContent(state, { opened += it }, { webShelf++ }, { scans++ }, { podcasts += it }, { sites += it }, { removed += it }, { retries++ })
    }

    @Test fun shelvesRenderInOrderAndOpenBooksAndSites() {
        show(ShelfUiState(
            books = listOf(book(1)), loading = false, activity = mapOf("gutenberg:1" to 1L),
            popular = listOf(PopularShelf("popular", "Most popular", "", listOf(book(7)))),
            siteSources = listOf(FeedSource("s1", "Future Tools", "https://s1.example/feed", "https://s1.example", 0L)),
        ))
        compose.onNodeWithTag("shelf_grid").assertExists()
        compose.onNodeWithText("MY BOOKS", useUnmergedTree = true).assertExists()
        compose.onNodeWithTag("open_web_shelf").performClick()
        assertEquals(1, webShelf)
        listOf("mine", "continue", "popular", "sites").forEach { compose.onNodeWithTag("shelf_grid").performScrollToNode(hasTestTag("shelf_row_$it")) }
        compose.onNodeWithTag("shelf_grid").performScrollToNode(hasTestTag("shelf_row_popular"))
        compose.onNodeWithText("MOST POPULAR", useUnmergedTree = true).assertExists()
        compose.onNodeWithTag("shelf_book_gutenberg:7").performClick()
        assertEquals(listOf(book(7)), opened)
        compose.onNodeWithTag("shelf_grid").performScrollToNode(hasTestTag("shelf_row_sites"))
        compose.onNodeWithTag("shelf_site_s1").assertContentDescriptionContains("Future Tools", substring = true).performClick()
        assertEquals(listOf("s1"), sites)
    }

    @Test fun longPressOnMyBooksOffersPodcastAndRemove() {
        show(ShelfUiState(books = listOf(book(1)), loading = false))
        compose.onAllNodesWithTag("shelf_book_gutenberg:1").onFirst().performTouchInput { longClick() }
        compose.onNodeWithText("Make a podcast").assertExists()
        compose.onNodeWithText("Remove from shelf").performClick()
        assertEquals(listOf(book(1)), removed)
        assertTrue(opened.isEmpty())
    }

    @Test fun emptyShelfShowsFriendlyPlankScanAndPopularRetry() {
        show(ShelfUiState(loading = false, popularError = "Popular picks couldn't load. Try again when you're online."))
        compose.onNodeWithText("Save books from Search to see them here").assertExists()
        compose.onNodeWithTag("shelf_scan").performClick()
        assertEquals(1, scans)
        compose.onNodeWithTag("shelf_row_continue").assertDoesNotExist()
        compose.onNodeWithTag("shelf_row_sites").assertDoesNotExist()
        compose.onNodeWithText("Try again").performClick()
        assertEquals(1, retries)
    }

    @Test fun longRowsLoopAndShortRowsDont() {
        val many = (101..112).map(::book)
        show(ShelfUiState(loading = false, books = listOf(book(1)), popular = listOf(PopularShelf("popular", "Most popular", "", many))))
        compose.onAllNodesWithTag("shelf_book_gutenberg:1").assertCountEquals(1)
        compose.onNodeWithTag("shelf_grid").performScrollToNode(hasTestTag("shelf_row_popular"))
        // The row loops: the last book sits just before the first one.
        val first = compose.onNodeWithTag("shelf_book_gutenberg:101").fetchSemanticsNode().boundsInRoot
        val last = compose.onNodeWithTag("shelf_book_gutenberg:112").fetchSemanticsNode().boundsInRoot
        assertTrue(last.right <= first.left)
        compose.onNodeWithTag("shelf_row_popular").performTouchInput { swipeRight(startX = centerX - 60f, endX = centerX + 60f, durationMillis = 1_000) }
        val moved = compose.onNodeWithTag("shelf_book_gutenberg:112").fetchSemanticsNode().boundsInRoot
        assertTrue("scrolling back past the first book keeps going", moved.left > last.left)
    }

    @Test fun eachBookShowsItsOwnAuthors() {
        show(ShelfUiState(loading = false, books = listOf(book(1, listOf("Jane Austen")), book(2, listOf("Herman Melville")), book(3, listOf("Karl Marx", "Friedrich Engels")))))
        compose.onAllNodesWithTag("shelf_book_gutenberg:1").onFirst().assertContentDescriptionEquals("Book 1, Jane Austen")
        compose.onAllNodesWithTag("shelf_book_gutenberg:2").onFirst().assertContentDescriptionEquals("Book 2, Herman Melville")
        compose.onAllNodesWithTag("shelf_book_gutenberg:3").onFirst().assertContentDescriptionEquals("Book 3, Karl Marx, Friedrich Engels")
        compose.onAllNodesWithText("Herman Melville", useUnmergedTree = true).onFirst().assertExists()
    }

    private fun book(id: Int, authors: List<String> = listOf("Author")) =
        Book("gutenberg:$id", BookSource.GUTENBERG, "$id", "Book $id", authors, null, emptyList(), null, listOf("en"), null, null)
}
