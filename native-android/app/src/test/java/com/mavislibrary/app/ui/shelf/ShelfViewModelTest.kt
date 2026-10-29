package com.mavislibrary.app.ui.shelf

import androidx.lifecycle.ViewModelStore
import com.mavislibrary.app.data.api.ShelfDef
import com.mavislibrary.app.data.api.TechItem
import com.mavislibrary.app.data.feeds.FeedSource
import com.mavislibrary.app.data.feeds.FeedStory
import com.mavislibrary.app.data.model.Book
import com.mavislibrary.app.data.model.BookSource
import com.mavislibrary.app.data.repo.CuratedCatalog
import com.mavislibrary.app.data.repo.Loaded
import com.mavislibrary.app.data.repo.Origin
import com.mavislibrary.app.data.repo.SavedShelf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ShelfViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val store = ViewModelStore()
    private val shelf = FakeShelf()
    private val catalog = FakeCatalog()

    @Before fun setup() { Dispatchers.setMain(dispatcher) }
    @After fun teardown() { store.clear(); Dispatchers.resetMain() }

    private fun viewModel() = ShelfViewModel(shelf, catalog).also { store.put("shelf", it) }

    @Test fun savedBooksKeepRepositoryOrderAndObserveUpdates() = runTest(dispatcher) {
        shelf.shelfBooks.value = listOf(book(3), book(1), book(2))
        val vm = viewModel()
        advanceUntilIdle()
        assertEquals(listOf(book(3), book(1), book(2)), vm.state.value.books)
        assertFalse(vm.state.value.loading)
        assertEquals("popular rows load even with saved books", 1, catalog.calls)
        assertEquals(2, vm.state.value.popular.size)
        shelf.shelfBooks.value = listOf(book(2), book(3))
        advanceUntilIdle()
        assertEquals(listOf(book(2), book(3)), vm.state.value.books)
    }

    @Test fun emptyShelfLoadsCuratedRowsAndTheirBookOrder() = runTest(dispatcher) {
        val vm = viewModel()
        advanceUntilIdle()
        assertEquals(1, catalog.calls)
        assertEquals(listOf("popular", "kids"), vm.state.value.popular.map { it.id })
        assertEquals(listOf(book(2), book(1)), vm.state.value.popular[0].books)
        assertEquals(listOf(book(3)), vm.state.value.popular[1].books)
        assertEquals(setOf(listOf(2, 1), listOf(3)), catalog.requested.toSet())
        assertFalse(vm.state.value.popularLoading)
    }

    @Test fun removingLastSavedBookUpdatesStateAndLoadsPopularPicks() = runTest(dispatcher) {
        shelf.shelfBooks.value = listOf(book(1))
        val vm = viewModel()
        advanceUntilIdle()
        vm.remove(book(1))
        advanceUntilIdle()
        assertEquals(listOf("gutenberg:1"), shelf.removed)
        assertTrue(vm.state.value.books.isEmpty())
        assertEquals(2, vm.state.value.popular.size)
        assertEquals(1, catalog.calls)
    }

    @Test fun failedRemovalKeepsSavedBookAndReportsError() = runTest(dispatcher) {
        shelf.shelfBooks.value = listOf(book(1))
        shelf.failRemove = true
        val vm = viewModel()
        advanceUntilIdle()
        vm.remove(book(1))
        advanceUntilIdle()
        assertEquals(listOf(book(1)), vm.state.value.books)
        assertNotNull(vm.state.value.error)
    }

    @Test fun curatedFailureCanBeRetried() = runTest(dispatcher) {
        catalog.fail = true
        val vm = viewModel()
        advanceUntilIdle()
        assertNotNull(vm.state.value.popularError)
        assertFalse(vm.state.value.popularLoading)
        catalog.fail = false
        vm.loadPopular()
        advanceUntilIdle()
        assertNull(vm.state.value.popularError)
        assertEquals(2, vm.state.value.popular.size)
    }

    @Test fun continueReadingFollowsActivityAndSitesCountUnread() = runTest(dispatcher) {
        shelf.shelfBooks.value = listOf(book(1), book(2), book(3))
        val activity = MutableStateFlow(mapOf("gutenberg:2" to 50L))
        val sites = FakeSites()
        val vm = ShelfViewModel(shelf, catalog, activity, sites).also { store.put("shelf", it) }
        advanceUntilIdle()
        assertEquals(listOf(book(2)), vm.state.value.continueReading)
        activity.value = mapOf("gutenberg:2" to 50L, "gutenberg:3" to 90L)
        advanceUntilIdle()
        assertEquals(listOf(book(3), book(2)), vm.state.value.continueReading)
        assertEquals(listOf("mine", "continue", "popular", "kids", "sites"), shelfRows(vm.state.value).map { it.id })
        assertEquals(listOf(SiteBook("on", "On", "https://on.example", 2), SiteBook("off", "Off", "https://off.example", 0)), vm.state.value.sites)
        assertEquals("disabled sites aren't fetched", listOf("on"), sites.loaded)
        sites.read.value = mapOf("on" to setOf("a", "b"))
        advanceUntilIdle()
        assertEquals(0, vm.state.value.sites.first().unread)
        assertEquals("reads don't refetch", listOf("on"), sites.loaded)
    }

    private class FakeSites : ShelfSites {
        override val sources = MutableStateFlow(listOf(
            FeedSource("on", "On", "https://on.example/feed", "https://on.example", 0L),
            FeedSource("off", "Off", "https://off.example/feed", "https://off.example", 0L, enabled = false)))
        override val read = MutableStateFlow<Map<String, Set<String>>>(emptyMap())
        val loaded = mutableListOf<String>()
        override suspend fun stories(source: FeedSource): List<FeedStory> {
            loaded += source.id
            return listOf("a", "b").map { FeedStory(TechItem(it, it, "https://on.example/$it"), 1L) }
        }
    }

    private class FakeShelf : SavedShelf {
        override val shelfBooks = MutableStateFlow<List<Book>>(emptyList())
        val removed = mutableListOf<String>()
        var failRemove = false
        override suspend fun remove(key: String) {
            if (failRemove) error("Disk unavailable")
            removed += key
            shelfBooks.value = shelfBooks.value.filterNot { it.key == key }
        }
    }

    private class FakeCatalog : CuratedCatalog {
        var calls = 0
        var fail = false
        val requested = mutableListOf<List<Int>>()
        override suspend fun curatedShelves(kids: Boolean): Loaded<List<ShelfDef>> {
            calls++
            if (fail) error("Offline")
            return Loaded(listOf(ShelfDef("popular", "Popular", ids = listOf(2, 1)),
                ShelfDef("kids", "Children's books", ids = listOf(3))), Origin.NETWORK, 0)
        }
        override suspend fun booksByIds(ids: List<Int>): Loaded<List<Book>> {
            requested += ids
            return Loaded(ids.map { book(it) }, Origin.NETWORK, 0)
        }
    }

    companion object {
        private fun book(id: Int) = Book("gutenberg:$id", BookSource.GUTENBERG, "$id", "Book $id",
            listOf("Author"), null, emptyList(), null, listOf("en"), null, null)
    }
}
