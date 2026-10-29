package com.mavislibrary.app.ui.sites

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.mavislibrary.app.data.briefing.BriefingSettings
import com.mavislibrary.app.data.briefing.BriefingStore
import com.mavislibrary.app.data.feeds.*
import com.mavislibrary.app.data.podcast.PodcastApi
import com.mavislibrary.app.data.podsub.*
import com.mavislibrary.app.data.reader.ReaderApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.*
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.time.ZoneId
import java.util.concurrent.atomic.AtomicInteger

private class MirrorMigration : MigrationState {
    var value: List<String>? = null
    override suspend fun followed(account: String) = value
    override suspend fun complete(account: String, ids: List<String>) { value = ids }
}

private fun rule(id: String, source: String = "feed") =
    """{"id":"$id","name":"Site $id","feedUrl":"https://$id.example.com/rss","siteUrl":"https://$id.example.com/","mode":"top","count":2,"delivery":"briefing","priority":0,"source":"$source"}"""

/** Fake HTTP gateway for /api/podsub (GET/PUT), /api/podsub/today and /api/podsub/page. Nothing leaves the JVM. */
private class FakeGateway(var sites: List<String>, var fail: Boolean = false) {
    val gets = AtomicInteger()
    val puts = AtomicInteger()
    val pages = mutableListOf<String>()
    fun respond(req: Request): Response {
        val path = req.url.encodedPath
        if (fail) return reply(req, """{"message":"down"}""", 503)
        return when {
            path == "/api/podsub" && req.method == "PUT" -> {
                puts.incrementAndGet()
                reply(req, Buffer().apply { req.body!!.writeTo(this) }.readUtf8())
            }
            path == "/api/podsub" -> { gets.incrementAndGet(); reply(req, """{"version":1,"sites":[${sites.joinToString(",")}],"updatedAt":"saved"}""") }
            path == "/api/podsub/today" -> reply(req, "[]")
            path == "/api/podsub/page" -> {
                synchronized(pages) { pages += req.url.queryParameter("url")!! }
                reply(req, """{"posts":[{"id":"p1","title":"Page story","link":"https://page.example.com/p1","publishedAt":5}]}""")
            }
            else -> reply(req, "{}", 404)
        }
    }
    private fun reply(req: Request, body: String, code: Int = 200) = Response.Builder().request(req).protocol(Protocol.HTTP_1_1)
        .code(code).message("fixture").body(body.toResponseBody("application/json".toMediaType())).build()
}

@OptIn(ExperimentalCoroutinesApi::class)
@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)
@org.robolectric.annotation.Config(sdk = [35])
class SitesMirrorTest {
    @get:Rule val temp = TemporaryFolder()
    @Before fun setUp() { Dispatchers.setMain(StandardTestDispatcher()) }
    @After fun tearDown() { Dispatchers.resetMain() }

    private val xml = "<rss><channel><title>Feed</title><item><title>Post</title><link>https://feed.example.com/post</link></item></channel></rss>"

    private suspend fun TestScope.viewModel(name: String, gateway: FakeGateway, seed: suspend (FeedStore) -> Unit = {}): Pair<SitesViewModel, FeedStore> {
        val data = PreferenceDataStoreFactory.create(scope = backgroundScope) { temp.root.resolve("$name.preferences_pb") }
        val store = FeedStore(data) { 1_000L }
        seed(store)
        val podcast = PodcastApi("https://gateway.example", execute = { gateway.respond(it) }, token = { "fixture-session" })
        val repo = PodsubRepository(PodsubApi(podcast), MirrorMigration(), { "uid1" }, { store.sources.first() }, { BriefingSettings() }, { ZoneId.of("UTC") })
        val feeds = FeedFetcher { FeedResponse(it, 200, xml) }
        val vm = SitesViewModel(store, FeedDiscovery(feeds), FeedRepository(feeds, { true }, reader = ReaderApi(podcast)), BriefingStore(data),
            podsub = repo, reader = ReaderApi(podcast))
        return vm to store
    }

    private suspend fun TestScope.settle(done: () -> Boolean) {
        withContext(Dispatchers.Default) { withTimeout(10_000) { while (!done()) delay(10) } }
        advanceUntilIdle()
    }

    @Test fun emptyPhoneMirrorsThreeServerSitesAndPageSitesUsePageReader() = runTest {
        val gateway = FakeGateway(listOf(rule("a"), rule("b"), rule("page", source = "page")))
        val (vm, store) = viewModel("mirror_empty", gateway)
        val state = vm.state.first { it.sources.size == 3 && it.posts.size == 3 }
        assertEquals(listOf("a", "b", "page"), state.sources.map { it.id })
        assertEquals("Site a", state.sources.first().name)
        assertEquals("https://a.example.com/", state.sources.first().siteUrl)
        assertEquals(2, state.sources.first().topCount)
        val page = store.sources.first().single { it.id == "page" }
        assertEquals("page", page.source)
        assertEquals("Page story", state.posts.getValue("page").single().item.title)
        assertEquals(listOf("https://page.example.com/rss"), synchronized(gateway.pages) { gateway.pages.toList() })
        assertNull(state.error)
    }

    @Test fun overlapIsNotDuplicatedAndLocalOnlySitesStay() = runTest {
        val gateway = FakeGateway(listOf(rule("a"), rule("b")))
        val (vm, store) = viewModel("mirror_overlap", gateway) {
            it.add(DiscoveryResult.Found("https://a.example.com/rss", "My A", 3, null, "https://a.example.com/"))
            it.add(DiscoveryResult.Found("https://local.example.com/rss", "Local", 3, null, "https://local.example.com/"))
        }
        settle { gateway.gets.get() >= 1 }
        val sources = vm.state.first { it.sources.size == 3 }.sources
        assertEquals(listOf("My A", "Local", "Site b"), sources.map { it.name })
        assertEquals(1, sources.count { it.feedUrl == "https://a.example.com/rss" })
        // Refresh mirrors again (one more GET) but adds nothing new.
        gateway.sites = listOf(rule("b"))
        val before = gateway.gets.get()
        vm.refresh()
        settle { gateway.gets.get() > before }
        assertEquals(3, store.sources.first().size)
    }

    @Test fun refreshPicksUpNewServerSites() = runTest {
        val gateway = FakeGateway(listOf(rule("a")))
        val (vm, _) = viewModel("mirror_refresh", gateway)
        vm.state.first { it.sources.size == 1 }
        gateway.sites = listOf(rule("a"), rule("c"))
        vm.refresh()
        assertEquals(listOf("a", "c"), vm.state.first { it.sources.size == 2 }.sources.map { it.id })
    }

    @Test fun failedFetchLeavesLocalListUntouchedWithoutError() = runTest {
        val gateway = FakeGateway(listOf(rule("a")), fail = true)
        val (vm, store) = viewModel("mirror_fail", gateway) {
            it.add(DiscoveryResult.Found("https://local.example.com/rss", "Local", 3, null, "https://local.example.com/"))
        }
        val before = store.sources.first()
        vm.state.first { it.sources.size == 1 }
        withContext(Dispatchers.Default) { delay(300) }
        advanceUntilIdle()
        assertEquals(before, store.sources.first())
        assertNull(vm.state.value.error)
        assertEquals(0, gateway.puts.get())
    }
}
