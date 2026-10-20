package com.mavislibrary.app.ui.sites

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.mavislibrary.app.data.briefing.BriefingStore
import com.mavislibrary.app.data.feeds.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

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
}
