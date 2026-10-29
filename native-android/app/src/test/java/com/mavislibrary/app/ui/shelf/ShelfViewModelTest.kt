package com.mavislibrary.app.ui.shelf

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.lifecycle.ViewModelStore
import com.mavislibrary.app.data.api.ShelfDef
import com.mavislibrary.app.data.api.TechItem
import com.mavislibrary.app.data.briefing.BriefingSettings
import com.mavislibrary.app.data.feeds.DiscoveryResult
import com.mavislibrary.app.data.feeds.FeedFetcher
import com.mavislibrary.app.data.feeds.FeedRepository
import com.mavislibrary.app.data.feeds.FeedResponse
import com.mavislibrary.app.data.feeds.FeedSource
import com.mavislibrary.app.data.feeds.FeedStore
import com.mavislibrary.app.data.feeds.FeedStory
import com.mavislibrary.app.data.model.Book
import com.mavislibrary.app.data.model.BookSource
import com.mavislibrary.app.data.podcast.PodcastApi
import com.mavislibrary.app.data.podsub.MigrationState
import com.mavislibrary.app.data.podsub.PodsubApi
import com.mavislibrary.app.data.podsub.PodsubRepository
import com.mavislibrary.app.data.podsub.toFeedSource
import com.mavislibrary.app.data.reader.ReadStateStore
import com.mavislibrary.app.data.reader.ReaderApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.ZoneId

import com.mavislibrary.app.data.repo.CuratedCatalog
import com.mavislibrary.app.data.repo.Loaded
import com.mavislibrary.app.data.repo.Origin
import com.mavislibrary.app.data.repo.SavedShelf

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ShelfViewModelTest {
    @get:Rule val temp = TemporaryFolder()
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

    @Test fun realFeedStoreMirrorsServerSitesAndRetriesWhenOwnerUnlocks() = runTest(dispatcher) {
        val data = PreferenceDataStoreFactory.create(scope = backgroundScope) { temp.root.resolve("shelf_sites.preferences_pb") }
        val feedStore = FeedStore(data) { 1_000L }
        val readState = ReadStateStore(data)
        val local = feedStore.add(DiscoveryResult.Found("https://local.example/rss", "Local Tech", 2, null, "https://local.example"))
        var ownerCode: String? = null
        var online = false
        val rss = "<rss><channel><title>Feed</title>" +
            "<item><guid>s1</guid><title>First</title><link>https://example.org/1</link></item>" +
            "<item><guid>s2</guid><title>Second</title><link>https://example.org/2</link></item>" +
            "</channel></rss>"
        val podcastApi = PodcastApi("https://gateway.example", execute = { req: Request ->
            val body = when (req.url.encodedPath) {
                "/api/podsub" -> """{"version":1,"sites":[
                    {"id":"srv","name":"Future Tools","feedUrl":"https://ft.example/rss","siteUrl":"https://ft.example/","mode":"top","count":1,"delivery":"briefing","priority":0,"source":"feed"},
                    {"id":"pg","name":"AI Watch","feedUrl":"https://ai.example/news","siteUrl":"https://ai.example/","mode":"top","count":1,"delivery":"briefing","priority":1,"source":"page"}
                ],"updatedAt":"saved"}"""
                "/api/podsub/page" -> """{"posts":[{"id":"p1","title":"Page Post","link":"https://ai.example/p1","publishedAt":9}]}"""
                else -> "{}"
            }
            Response.Builder().request(req).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(body.toResponseBody("application/json".toMediaType())).build()
        }, owner = { ownerCode }, token = { null })
        val podsub = PodsubRepository(
            PodsubApi(podcastApi),
            object : MigrationState {
                var saved: List<String>? = null
                override suspend fun followed(account: String) = saved
                override suspend fun complete(account: String, ids: List<String>) { saved = ids }
            },
            { if (ownerCode != null) "owner" else null },
            { feedStore.sources.first() },
            { BriefingSettings() },
            { ZoneId.of("UTC") },
        )
        val feedRepo = FeedRepository(FeedFetcher { FeedResponse(it, 200, rss) }, { online }, reader = ReaderApi(podcastApi))
        val shelfSites = object : ShelfSites {
            override val sources = feedStore.sources
            override val read = readState.read
            override suspend fun stories(source: FeedSource) = feedRepo.load(listOf(source)).stories
            override suspend fun mirror() {
                try { feedStore.addMissing(podsub.serverSites().map { it.toFeedSource() }) }
                catch (e: CancellationException) { throw e } catch (_: Exception) {}
            }
        }
        val vm = ShelfViewModel(shelf, catalog, sites = shelfSites).also { store.put("shelf_real", it) }
        vm.onScreenShown() // initial composition call (skipped because init already started)
        withContext(Dispatchers.Default) { withTimeout(5_000) { while (vm.state.value.sites.isEmpty()) { advanceUntilIdle(); delay(10) } } }
        advanceUntilIdle()
        // While offline and without owner code, only the local site exists with 0 loaded stories.
        assertEquals(listOf(SiteBook(local.id, "Local Tech", "https://local.example/", 0)), vm.state.value.sites)

        // Now network comes online and owner code is entered; returning to Shelf triggers onScreenShown -> syncSites.
        online = true
        ownerCode = "owner-secret"
        vm.onScreenShown()
        withContext(Dispatchers.Default) {
            withTimeout(5_000) {
                while (vm.state.value.sites.size < 3 || vm.state.value.sites.any { it.unread == 0 }) {
                    advanceUntilIdle()
                    delay(10)
                }
            }
        }
        advanceUntilIdle()
        assertEquals(
            listOf(
                SiteBook(local.id, "Local Tech", "https://local.example/", 2),
                SiteBook("srv", "Future Tools", "https://ft.example/", 2),
                SiteBook("pg", "AI Watch", "https://ai.example/", 1),
            ),
            vm.state.value.sites,
        )
        val firstStoryId = vm.state.value.sitePosts.getValue("srv").first().item.id
        readState.markRead("srv", listOf(firstStoryId))
        withContext(Dispatchers.Default) {
            withTimeout(5_000) {
                while (vm.state.value.sites.first { it.id == "srv" }.unread != 1) {
                    advanceUntilIdle()
                    delay(10)
                }
            }
        }
        assertEquals(1, vm.state.value.sites.first { it.id == "srv" }.unread)
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
