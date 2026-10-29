package com.mavislibrary.app.ui.sites

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.mavislibrary.app.data.briefing.BriefingSettings
import com.mavislibrary.app.data.briefing.BriefingStore
import com.mavislibrary.app.data.feeds.*
import com.mavislibrary.app.data.podcast.*
import com.mavislibrary.app.data.podsub.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
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

private class FakePodsubService : PodsubService {
    var response = SubscriptionResponse(
        version = 1,
        sites = listOf(SiteRule("blog", "Blog", "https://blog.com/feed", "https://blog.com/", mode = "top", count = 1, delivery = "briefing", priority = 0)),
        updatedAt = "init",
    )
    var puts = 0
    override suspend fun get() = response
    override suspend fun put(document: SubscriptionDocument): SubscriptionResponse {
        puts++
        response = response.copy(sites = document.sites, updatedAt = "saved")
        return response
    }
    override suspend fun today() = emptyList<PodcastJob>()
    override suspend fun runs() = emptyList<SubscriptionRun>()
    override suspend fun runNow() = SubscriptionRun("now", status = "ok")
}

private class FakeMigration : MigrationState {
    var value: List<String>? = listOf("blog")
    override suspend fun followed(account: String) = value
    override suspend fun complete(account: String, ids: List<String>) { value = ids }
}

private fun fakeResponse(request: Request, body: String, code: Int = 200) =
    Response.Builder()
        .request(request)
        .protocol(Protocol.HTTP_1_1)
        .code(code)
        .message("OK")
        .body(body.toResponseBody("application/json".toMediaType()))
        .build()

@OptIn(ExperimentalCoroutinesApi::class)
@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)
@org.robolectric.annotation.Config(sdk = [35])
class SitesViewModelTest {
    @get:Rule val temp = TemporaryFolder()
    @Before fun setUp() { Dispatchers.setMain(StandardTestDispatcher()) }
    @After fun tearDown() { Dispatchers.resetMain() }

    @Test fun followLoadsPostsCountsNewKeepsChoicesAndUnfollows() = runTest {
        val data = PreferenceDataStoreFactory.create(scope = backgroundScope) { temp.root.resolve("sites.preferences_pb") }
        val xml = "<rss><channel><title>My AI</title><item><title>Story</title><link>https://example.com/post</link>" +
            "<pubDate>Fri, 09 Oct 2026 12:00:00 +0000</pubDate></item></channel></rss>"
        val feeds = FeedFetcher { url -> FeedResponse(url, 200, xml) }
        var reschedules = 0
        val vm = SitesViewModel(FeedStore(data) { 1_000L }, FeedDiscovery(feeds), FeedRepository(feeds, { true }), BriefingStore(data)) { reschedules++ }
        vm.openAddSite(); vm.siteInput("example.com"); vm.findFeed()
        advanceUntilIdle()
        assertTrue(vm.state.value.feedResult is DiscoveryResult.Found)
        vm.addFeed()
        val loaded = vm.state.first { it.sources.isNotEmpty() && it.posts.isNotEmpty() }
        val site = loaded.sources.single()
        assertEquals("My AI", site.name)
        assertFalse(loaded.addOpen)
        assertEquals("Story", loaded.posts.getValue(site.id).single().item.title)
        assertEquals(1, loaded.newCount(site))
        assertEquals(SitePodcast.BRIEFING, site.podcast)

        vm.setPodcast(site.id, SitePodcast.OWN)
        vm.state.first { it.sources.single().podcast == SitePodcast.OWN }
        vm.setBriefing(true)
        vm.state.first { it.briefing.enabled }
        advanceUntilIdle()
        assertEquals(1, reschedules)

        vm.remove(site.id)
        vm.state.first { it.sources.isEmpty() && it.posts.isEmpty() }
    }

    @Test fun overflowMenuUpdatesTopNAndDeliveryAndUnfollows() = runTest {
        val data = PreferenceDataStoreFactory.create(scope = backgroundScope) { temp.root.resolve("sites_overflow.preferences_pb") }
        val store = FeedStore(data) { 1_000L }
        val xml = "<rss><channel><title>Blog</title><item><title>Post</title><link>https://blog.com/1</link></item></channel></rss>"
        val feeds = FeedFetcher { FeedResponse(it, 200, xml) }
        val podsubService = FakePodsubService()
        val podsubRepo = PodsubRepository(podsubService, FakeMigration(), { "uid1" }, { store.sources.first() }, { BriefingSettings() }, { ZoneId.of("UTC") })

        val vm = SitesViewModel(store, FeedDiscovery(feeds), FeedRepository(feeds, { true }), BriefingStore(data), podsub = podsubRepo)
        vm.openAddSite(); vm.siteInput("blog.com"); vm.findFeed()
        advanceUntilIdle()
        vm.addFeed()

        val loaded = vm.state.first { it.sources.isNotEmpty() }
        val site = loaded.sources.single()

        // 1. Top N -> Off
        vm.setTopCount(site.id, 0)
        val offState = vm.state.first { it.siteRules[site.id]?.mode == "off" }
        val offSite = offState.sources.single()
        assertEquals(SitePodcast.OFF, offSite.podcast)
        assertEquals(0, offSite.topCount)
        assertEquals("off", podsubService.response.sites.find { it.id == site.id }?.mode)
        assertNull(podsubService.response.sites.find { it.id == site.id }?.count)

        // 2. Top N -> 3
        vm.setTopCount(site.id, 3)
        val top3State = vm.state.first { it.siteRules[site.id]?.count == 3 }
        val top3Site = top3State.sources.single()
        assertEquals(3, top3Site.topCount)
        assertEquals(SitePodcast.BRIEFING, top3Site.podcast)
        assertEquals("top", podsubService.response.sites.find { it.id == site.id }?.mode)
        assertEquals(3, podsubService.response.sites.find { it.id == site.id }?.count)

        // 3. Delivery -> Separate
        vm.setDelivery(site.id, "separate")
        val sepState = vm.state.first { it.siteRules[site.id]?.delivery == "separate" }
        assertEquals(SitePodcast.OWN, sepState.sources.single().podcast)
        assertEquals("separate", podsubService.response.sites.find { it.id == site.id }?.delivery)

        // 4. Delivery -> Briefing
        vm.setDelivery(site.id, "briefing")
        val briefState = vm.state.first { it.siteRules[site.id]?.delivery == "briefing" }
        assertEquals(SitePodcast.BRIEFING, briefState.sources.single().podcast)
        assertEquals("briefing", podsubService.response.sites.find { it.id == site.id }?.delivery)

        // 5. Unfollow
        vm.remove(site.id)
        val emptyState = vm.state.first { it.sources.isEmpty() && it.siteRules[site.id] == null }
        assertTrue(emptyState.sources.isEmpty())
        assertTrue(podsubService.response.sites.none { it.id == site.id })
    }

    @Test fun makeArticlePodcastInstantFeedbackAndBlocksDoubleTap() = runTest {
        val data = PreferenceDataStoreFactory.create(scope = backgroundScope) { temp.root.resolve("sites_article.preferences_pb") }
        val store = FeedStore(data) { 1_000L }
        val xml = "<rss><channel><title>Site</title><item><title>Breaking AI</title><link>https://example.com/article</link><description>Snippet text</description></item></channel></rss>"
        val feeds = FeedFetcher { FeedResponse(it, 200, xml) }

        val createdRequests = mutableListOf<Request>()
        val podcastApi = PodcastApi("https://example.com", execute = { req ->
            createdRequests += req
            when (req.url.encodedPath) {
                "/api/podcast/create" -> fakeResponse(req, """{"id":"j_article","status":"queued","queuePosition":1,"etaMin":5}""", 201)
                "/api/podcast/list" -> fakeResponse(req, """{"jobs":[{"id":"j_article","status":"queued","queuePosition":1,"etaMin":5}]}""", 200)
                else -> fakeResponse(req, "{}", 200)
            }
        }, token = { "tok" })

        val vm = SitesViewModel(store, FeedDiscovery(feeds), FeedRepository(feeds, { true }), BriefingStore(data), podcastApi = podcastApi)
        vm.openAddSite(); vm.siteInput("example.com"); vm.findFeed()
        advanceUntilIdle()
        vm.addFeed()
        val state = vm.state.first { it.sources.isNotEmpty() && it.posts.isNotEmpty() }
        val site = state.sources.single()
        val story = state.posts.getValue(site.id).single()

        // Make podcast tap
        vm.makeArticlePodcast(site, story)

        // Try double-tap immediately while in flight
        vm.makeArticlePodcast(site, story)

        // Wait for queued feedback to arrive
        val queuedState = vm.state.first { it.queuedStatus.containsKey(story.item.id) }

        // Verified: only one create request was dispatched (double tap blocked)
        assertEquals(1, createdRequests.filter { it.url.encodedPath == "/api/podcast/create" }.size)
        val createReq = createdRequests.first { it.url.encodedPath == "/api/podcast/create" }
        assertEquals("POST", createReq.method)

        // Instant feedback is set with queue position and eta
        val queuedText = queuedState.queuedStatus[story.item.id]
        assertEquals("Queued #1 (~5 min)", queuedText)
        assertFalse(queuedState.inFlightRequests.contains(story.item.id))
    }

    @Test fun makeCombinedPodcastCombinesArticlesAndCallsCreateOnce() = runTest {
        val data = PreferenceDataStoreFactory.create(scope = backgroundScope) { temp.root.resolve("sites_combined.preferences_pb") }
        val store = FeedStore(data) { 1_000L }
        val xml = "<rss><channel><title>Multi Blog</title>" +
            "<item><title>Post One</title><link>https://example.com/1</link><description>Summary one</description></item>" +
            "<item><title>Post Two</title><link>https://example.com/2</link><description>Summary two</description></item>" +
            "</channel></rss>"
        val feeds = FeedFetcher { FeedResponse(it, 200, xml) }

        var createCallCount = 0
        var sentBody = ""
        val podcastApi = PodcastApi("https://example.com", execute = { req ->
            when (req.url.encodedPath) {
                "/api/podcast/create" -> {
                    createCallCount++
                    val buffer = Buffer()
                    req.body?.writeTo(buffer)
                    sentBody = buffer.readUtf8()
                    fakeResponse(req, """{"id":"j_combined","status":"queued","queuePosition":2,"etaMin":10}""", 201)
                }
                "/api/podcast/list" -> fakeResponse(req, """{"jobs":[{"id":"j_combined","status":"queued","queuePosition":2,"etaMin":10}]}""", 200)
                else -> fakeResponse(req, "{}", 200)
            }
        }, token = { "tok" })

        val vm = SitesViewModel(store, FeedDiscovery(feeds), FeedRepository(feeds, { true }), BriefingStore(data), podcastApi = podcastApi)
        vm.openAddSite(); vm.siteInput("example.com"); vm.findFeed()
        advanceUntilIdle()
        vm.addFeed()
        val state = vm.state.first { it.sources.isNotEmpty() && it.posts[it.sources.single().id]?.size == 2 }
        val site = state.sources.single()
        val stories = state.posts.getValue(site.id)

        // Multi-select articles
        vm.toggleArticleSelection(stories[0].item.id)
        vm.toggleArticleSelection(stories[1].item.id)
        assertEquals(setOf(stories[0].item.id, stories[1].item.id), vm.state.value.selectedArticles)

        // Trigger make combined podcast
        vm.makeCombinedPodcast(site, stories)

        // Double-tap attempt
        vm.makeCombinedPodcast(site, stories)

        // Wait for combined queued feedback to arrive
        val queuedState = vm.state.first { it.queuedStatus.containsKey("combined_${site.id}") }

        // Exactly one POST /api/podcast/create was called
        assertEquals(1, createCallCount)
        assertTrue(sentBody.contains("Post One"))
        assertTrue(sentBody.contains("Post Two"))
        assertTrue(sentBody.contains("Summary one"))
        assertTrue(sentBody.contains("Summary two"))

        // Queued feedback is stored
        val feedback = queuedState.queuedStatus["combined_${site.id}"]
        assertEquals("Queued #2 (~10 min)", feedback)
        assertTrue(queuedState.selectedArticles.isEmpty())
        assertFalse(queuedState.inFlightRequests.contains("combined_${site.id}"))
    }
}
