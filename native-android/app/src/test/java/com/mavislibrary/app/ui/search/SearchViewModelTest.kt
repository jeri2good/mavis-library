package com.mavislibrary.app.ui.search

import com.mavislibrary.app.data.api.ApiException
import com.mavislibrary.app.data.api.Endpoints
import com.mavislibrary.app.data.api.RemoteSource
import com.mavislibrary.app.data.db.BookEntity
import com.mavislibrary.app.data.db.ShelfDao
import com.mavislibrary.app.data.db.ShelfEntryEntity
import com.mavislibrary.app.data.repo.CachedFetcher
import com.mavislibrary.app.data.repo.CachedResponse
import com.mavislibrary.app.data.repo.ResponseCache
import com.mavislibrary.app.data.repo.SearchRepository
import com.mavislibrary.app.data.repo.ShelfRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import com.mavislibrary.app.data.feeds.*
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

private class EmptyResponseCache : ResponseCache {
    override suspend fun get(key: String): CachedResponse? = null
    override suspend fun put(key: String, body: String, fetchedAt: Long) = Unit
}

private class FakeShelfDao : ShelfDao {
    override suspend fun upsertBook(book: BookEntity) = Unit
    override suspend fun insertEntry(entry: ShelfEntryEntity): Long = 1L
    override suspend fun remove(key: String) = Unit
    override fun keys(): Flow<List<String>> = flowOf(emptyList())
    override fun books(): Flow<List<BookEntity>> = flowOf(emptyList())
    override suspend fun count(): Int = 0
}

@OptIn(ExperimentalCoroutinesApi::class)
@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)
@org.robolectric.annotation.Config(sdk = [35])
class SearchViewModelTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun techSitesRequireLoadedAdultMode() {
        assertTrue(techSitesAllowed(false))
        org.junit.Assert.assertFalse(techSitesAllowed(true))
        org.junit.Assert.assertFalse(techSitesAllowed(null))
    }

    @Test fun addSiteUpdatesChipsAndLoadsItemsThenDisableRemovesSelection() = runTest {
        val data = PreferenceDataStoreFactory.create(scope = backgroundScope) { temp.root.resolve("feeds.preferences_pb") }
        val store = FeedStore(data)
        val feeds = FeedFetcher { url -> FeedResponse(url, 200, "<rss><channel><title>My AI</title><item><title>Story</title><link>https://example.com/post</link></item></channel></rss>") }
        val vm = SearchViewModel(
            SearchRepository(CachedFetcher(RemoteSource { path, _ -> if (path == Endpoints.SHELVES) shelves() else tech("ai") }, EmptyResponseCache(), { true })),
            ShelfRepository(FakeShelfDao()), store, FeedDiscovery(feeds), FeedRepository(feeds, { true }),
        )
        vm.openAddSite(); vm.siteInput("example.com"); vm.findFeed()
        advanceUntilIdle()
        assertTrue(vm.state.value.feedResult is DiscoveryResult.Found)
        vm.addFeed()
        vm.state.first { it.sources.isNotEmpty() && it.tech?.firstOrNull()?.title == "Story" }
        assertEquals(1, vm.state.value.sources.size)
        assertEquals("My AI", vm.state.value.sources.single().name)
        assertEquals("Story", vm.state.value.tech!!.single().title)
        org.junit.Assert.assertFalse(vm.state.value.addSiteOpen)
        vm.enableFeed(vm.state.value.sources.single().id, false)
        vm.state.first { it.techKind == "mine" && it.tech != null }
        assertEquals("mine", vm.state.value.techKind)
        assertTrue(vm.state.value.tech!!.isEmpty())
    }

    @Before fun setUp() { Dispatchers.setMain(StandardTestDispatcher()) }
    @After fun tearDown() { Dispatchers.resetMain() }

    private fun model(remote: RemoteSource) = SearchViewModel(
        SearchRepository(CachedFetcher(remote, EmptyResponseCache(), { true })),
        ShelfRepository(FakeShelfDao()),
    )

    private fun shelves(vararg ids: Int): String = """{"shelves":[${ids.joinToString(",") { """{"id":"s$it","label":"Shelf $it","blurb":"About $it","ids":[$it]}""" }}]}"""
    private fun book(id: Int): String = """{"results":[{"id":$id,"title":"Book $id"}]}"""
    private fun tech(kind: String, count: Int = 1): String = """{"kind":"$kind","items":[${(1..count).joinToString(",") { """{"id":"$kind$it","title":"$kind $it","url":"https://example.org/$it"}""" }}]}"""

    @Test fun discoverLoadsDefinitionsThenEachShelfWithoutChangingOrder() = runTest {
        val pending = CompletableDeferred<String>()
        val vm = model(RemoteSource { path, params -> when (path) {
            Endpoints.SHELVES -> shelves(1, 2)
            Endpoints.TECH -> tech("ai")
            else -> if (params.single().second == "1") pending.await() else book(2)
        } })
        runCurrent()
        assertEquals(listOf("s1", "s2"), vm.state.value.discover.map { it.id })
        assertEquals(listOf("Shelf 1", "Shelf 2"), vm.state.value.discover.map { it.label })
        assertNull(vm.state.value.discover[0].books)
        assertEquals("Book 2", vm.state.value.discover[1].books!!.single().title)
        assertNull(vm.state.value.discoverError)
        pending.complete(book(1))
        runCurrent()
        assertEquals(listOf("Book 1", "Book 2"), vm.state.value.discover.map { it.books!!.single().title })
        assertEquals("ai 1", vm.state.value.tech!!.single().title)
    }

    @Test fun failedShelfBecomesEmptyWithoutDiscardingOtherShelves() = runTest {
        val vm = model(RemoteSource { path, params -> when (path) {
            Endpoints.SHELVES -> shelves(1, 2, 3)
            Endpoints.TECH -> tech("ai")
            else -> if (params.single().second == "2") throw ApiException("unavailable", "One shelf failed", status = 400)
                else book(params.single().second.toInt())
        } })
        runCurrent()
        assertEquals(listOf("Book 1", null, "Book 3"), vm.state.value.discover.map { it.books?.singleOrNull()?.title })
        assertTrue(vm.state.value.discover[1].books!!.isEmpty())
        assertNull(vm.state.value.discoverError)
    }

    @Test fun failedShelfDefinitionsReportErrorWithoutStoppingTech() = runTest {
        val vm = model(RemoteSource { path, _ -> when (path) {
            Endpoints.SHELVES -> throw ApiException("unavailable", "Discover unavailable", status = 400)
            Endpoints.TECH -> tech("ai")
            else -> error("unexpected request")
        } })
        runCurrent()
        assertEquals("Discover unavailable", vm.state.value.discoverError)
        assertTrue(vm.state.value.discover.isEmpty())
        assertEquals(1, vm.state.value.tech!!.size)
    }

    @Test fun noMoreThanThreeShelfRequestsRunTogether() = runTest {
        val waiting = (1..5).associateWith { CompletableDeferred<String>() }
        val started = mutableListOf<Int>()
        var active = 0
        var peak = 0
        val vm = model(RemoteSource { path, params -> when (path) {
            Endpoints.SHELVES -> shelves(1, 2, 3, 4, 5)
            Endpoints.TECH -> tech("ai")
            else -> {
                val id = params.single().second.toInt()
                started += id
                active++
                peak = maxOf(peak, active)
                try { waiting.getValue(id).await() } finally { active-- }
            }
        } })
        runCurrent()
        assertEquals(listOf(1, 2, 3), started)
        assertEquals(3, active)
        waiting.getValue(1).complete(book(1))
        runCurrent()
        assertEquals(listOf(1, 2, 3, 4), started)
        assertEquals(3, active)
        waiting.getValue(2).complete(book(2))
        runCurrent()
        assertEquals(listOf(1, 2, 3, 4, 5), started)
        (3..5).forEach { waiting.getValue(it).complete(book(it)) }
        runCurrent()
        assertEquals(3, peak)
        assertEquals(0, active)
        assertEquals((1..5).map { "Book $it" }, vm.state.value.discover.map { it.books!!.single().title })
    }

    @Test fun techIsLimitedToTwelveAndSwitchingTabsIgnoresOldSuccess() = runTest {
        val old = CompletableDeferred<String>()
        val vm = model(RemoteSource { path, params -> when (path) {
            Endpoints.SHELVES -> shelves()
            Endpoints.TECH -> if (params.single().second == "ai") old.await() else tech("papers", 15)
            else -> error("unexpected request")
        } })
        runCurrent()
        vm.loadTech("papers")
        assertNull(vm.state.value.tech)
        runCurrent()
        assertEquals("papers", vm.state.value.techKind)
        assertEquals(12, vm.state.value.tech!!.size)
        old.complete(tech("ai"))
        runCurrent()
        assertEquals("papers 1", vm.state.value.tech!!.first().title)
        assertNull(vm.state.value.techError)
    }

    @Test fun switchingTabsIgnoresOldErrorsAndReportsCurrentError() = runTest {
        val old = CompletableDeferred<String>()
        val vm = model(RemoteSource { path, params -> when (path) {
            Endpoints.SHELVES -> shelves()
            Endpoints.TECH -> if (params.single().second == "ai") old.await()
                else throw ApiException("bad_response", "Papers unavailable")
            else -> error("unexpected request")
        } })
        runCurrent()
        vm.loadTech("papers")
        runCurrent()
        assertEquals("Papers unavailable", vm.state.value.techError)
        assertTrue(vm.state.value.tech!!.isEmpty())
        old.completeExceptionally(ApiException("bad_response", "Old AI error"))
        runCurrent()
        assertEquals("papers", vm.state.value.techKind)
        assertEquals("Papers unavailable", vm.state.value.techError)
    }
}
