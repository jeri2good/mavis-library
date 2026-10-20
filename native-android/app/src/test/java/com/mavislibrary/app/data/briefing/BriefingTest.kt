package com.mavislibrary.app.data.briefing

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.mavislibrary.app.data.api.ApiException
import com.mavislibrary.app.data.api.TechItem
import com.mavislibrary.app.data.feeds.FeedLoad
import com.mavislibrary.app.data.feeds.FeedSource
import com.mavislibrary.app.data.feeds.FeedStory
import com.mavislibrary.app.data.feeds.SitePodcast
import com.mavislibrary.app.data.podcast.PodcastJob
import com.mavislibrary.app.data.podcast.PodcastPreferences
import com.mavislibrary.app.data.podcast.PodcastRequest
import com.mavislibrary.app.data.podcast.SavedPodcast
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException
import java.time.LocalTime
import java.time.ZoneId

class BriefingTest {
    @get:Rule val temp = TemporaryFolder()
    private val hour = 3_600_000L
    private val now = 1_800_000_000_000L
    private val utc = ZoneId.of("UTC")

    private fun site(id: String, mode: String = SitePodcast.BRIEFING, enabled: Boolean = true) =
        FeedSource(id, "Site $id", "https://$id.example.com/feed", "https://$id.example.com/", 0, enabled, mode)
    private fun post(id: String, hoursAgo: Long?, body: String = "Body of $id. ".repeat(100)) =
        FeedStory(TechItem(id, "Post $id", "https://example.com/$id", summary = "About $id"), hoursAgo?.let { now - it * hour }, body)
    private fun TestScope.store(name: String = "briefing") =
        BriefingStore(PreferenceDataStoreFactory.create(scope = backgroundScope) { temp.root.resolve("$name.preferences_pb") })

    @Test fun firstLookTakesRecentPostsThenOnlyUnseenOnes() {
        val posts = listOf(post("a", 2), post("b", 30), post("c", 50), post("d", 24 * 9))
        assertEquals(listOf("a", "b"), Briefing.newPosts(posts, null, now).map { it.item.id })
        assertEquals(listOf("u1", "u2", "u3"), Briefing.newPosts((1..5).map { post("u$it", null) }, null, now).map { it.item.id })
        val cursor = Briefing.advance(null, posts)
        assertTrue(Briefing.newPosts(posts, cursor, now).isEmpty())
        val later = listOf(post("e", 1), post("f", null)) + posts
        assertEquals(listOf("e", "f"), Briefing.newPosts(later, cursor, now).map { it.item.id })
        assertEquals(200, Briefing.advance(SiteCursor((1..300).map { "old$it" }), posts).seen.size)
    }

    @Test fun planMakesOneBriefingAndPerSiteEpisodes() {
        val plan = Briefing.plan(listOf(
            SitePosts(site("a"), listOf(post("a1", 3), post("a2", 1))),
            SitePosts(site("b"), (1..8).map { post("b$it", it.toLong()) }),
            SitePosts(site("own1", SitePodcast.OWN), listOf(post("o1", 2, body = "Short teaser"))),
            SitePosts(site("own2", SitePodcast.OWN), listOf(post("o2", 2), post("o3", 4))),
            SitePosts(site("quiet", SitePodcast.OWN), emptyList()),
        ), now, utc, "debate", "short", "local")
        assertEquals(3, plan.size)
        val briefing = plan[0].request
        assertEquals("chapter", briefing.kind)
        assertTrue(briefing.title.startsWith("Daily briefing · "))
        assertEquals(listOf("a", "b"), plan[0].sites.map { it.site.id })
        assertTrue(briefing.text!!.contains("8 new posts from Site a and Site b"))
        assertFalse(briefing.text!!.contains("Post b7")) // six posts per site at most
        assertTrue(briefing.text!!.indexOf("Post a2") < briefing.text!!.indexOf("Post b3")) // newest first across sites
        assertEquals(listOf("debate", "short", "local"), listOf(briefing.style, briefing.length, briefing.engine))
        // A lone post with little text goes as its link so the studio reads the full article.
        assertEquals(PodcastRequest("article", "Site own1: Post o1", url = "https://example.com/o1", style = "debate", length = "short", engine = "local"), plan[1].request)
        assertEquals("chapter", plan[2].request.kind)
        assertTrue(plan[2].request.text!!.startsWith("New from Site own2"))
        plan.forEach { it.request.validated() }
        assertTrue(Briefing.plan(listOf(SitePosts(site("a"), emptyList())), now, utc, "deep-dive", "default", "auto").isEmpty())
    }

    @Test fun ownEpisodesAreCappedAndTextStopsAtTheLimit() {
        val own = (1..6).map { SitePosts(site("s$it", SitePodcast.OWN), listOf(post("p$it", 1), post("q$it", 2))) }
        assertEquals(Briefing.MAX_OWN_EPISODES, Briefing.plan(own, now, utc, "deep-dive", "default", "auto").size)
        val big = (1..30).map { site("x") to post("big$it", 1, body = "x".repeat(8_000)) }
        val text = Briefing.text("Heading", big, utc)
        assertTrue(text.length <= Briefing.MAX_TEXT)
        assertTrue(text.contains("Post 14 of 30") && !text.contains("Post 15 of 30"))
    }

    @Test fun storeKeepsSettingsPendingAndForgetsUnfollowedCursors() = runTest {
        val store = store()
        assertEquals(BriefingSettings(false, DEFAULT_BRIEFING_TIME), store.settings.first())
        store.setEnabled(true); store.setTime(LocalTime.of(7, 5, 30))
        assertEquals(BriefingSettings(true, LocalTime.of(7, 5)), store.settings.first())
        store.advance(mapOf("a" to SiteCursor(listOf("1")), "b" to SiteCursor(listOf("2"))), setOf("a", "b"))
        store.advance(emptyMap(), setOf("a"))
        assertEquals(mapOf("a" to SiteCursor(listOf("1"))), store.cursors())
        store.addPending(PendingEpisode("j1", "One", 1)); store.addPending(PendingEpisode("j1", "One again", 2))
        assertEquals(listOf(PendingEpisode("j1", "One again", 2)), store.pending.first())
        store.removePending("j1"); assertTrue(store.pending.first().isEmpty())
    }

    private class Studio(var fail: Exception? = null, val failAfter: Int = 0) {
        val requests = mutableListOf<PodcastRequest>()
        suspend fun create(request: PodcastRequest): SavedPodcast {
            fail?.takeIf { requests.size >= failAfter }?.let { throw it }
            requests += request
            return SavedPodcast(PodcastJob("job${requests.size}", "queued", title = request.title, kind = request.kind, style = request.style), "studio")
        }
    }
    private fun runner(store: BriefingStore, sites: List<FeedSource>, feeds: Map<String, FeedLoad>, studio: Studio) = BriefingRunner(
        sources = { sites }, load = { feeds.getValue(it.id) }, store = store, create = studio::create,
        defaults = { PodcastPreferences() }, clock = { now }, zone = { utc },
    )

    @Test fun runnerAsksTheStudioAndMovesOnOnlyAfterItAccepts() = runTest {
        val store = store()
        val sites = listOf(site("a"), site("own", SitePodcast.OWN), site("off", SitePodcast.OFF), site("quiet"))
        val feeds = mapOf("a" to FeedLoad(listOf(post("a1", 1))), "own" to FeedLoad(listOf(post("o1", 1), post("o2", 2))),
            "quiet" to FeedLoad(listOf(post("old", 24 * 3))))
        val studio = Studio()
        val outcome = runner(store, sites, feeds, studio).run()
        assertEquals(2, (outcome as BriefingOutcome.Started).titles.size)
        assertEquals(listOf("chapter", "chapter"), studio.requests.map { it.kind })
        assertEquals(listOf("job1", "job2"), store.pending.first().map { it.id })
        assertEquals(setOf("a", "own", "quiet"), store.cursors().keys)
        assertTrue(store.status.first()!!.message.startsWith("Started 2 podcasts"))
        // The same posts are not sent twice.
        assertEquals(BriefingOutcome.NothingNew, runner(store, sites, feeds, studio).run())
        assertEquals(2, studio.requests.size)
        assertEquals(BriefingOutcome.NoSites, runner(store, listOf(site("off", SitePodcast.OFF)), feeds, studio).run())
    }

    @Test fun signInLimitsAndUnreachableSitesAreReportedHonestly() = runTest {
        val sites = listOf(site("a"), site("own", SitePodcast.OWN))
        val feeds = mapOf("a" to FeedLoad(listOf(post("a1", 1))), "own" to FeedLoad(listOf(post("o1", 1), post("o2", 2))))
        val signedOut = store("signed-out")
        val blocked = runner(signedOut, sites, feeds, Studio(ApiException("sign_in_required", "Sign in to use Podcast Lounge", 401))).run()
        assertTrue((blocked as BriefingOutcome.Blocked).message.contains("Settings → Account"))
        assertTrue(signedOut.cursors().isEmpty())

        val limited = store("limited")
        val partial = runner(limited, sites, feeds, Studio(ApiException("daily_limit", "Daily limit reached", 429), failAfter = 1)).run()
        assertEquals(1, (partial as BriefingOutcome.Started).titles.size)
        assertEquals(setOf("a"), limited.cursors().keys) // the own-episode site waits for the next run
        assertTrue(limited.status.first()!!.message.startsWith("Daily limit reached"))

        val down = store("down")
        assertTrue(runCatching { runner(down, sites, feeds, Studio(IOException("Studio unavailable, try later"))).run() }.exceptionOrNull() is IOException)
        assertTrue(down.cursors().isEmpty())
        val offline = mapOf("a" to FeedLoad(emptyList(), "Couldn't load Site a."), "own" to FeedLoad(emptyList(), "Couldn't load Site own."))
        assertTrue(runCatching { runner(store("offline"), sites, offline, Studio()).run() }.exceptionOrNull() is IOException)
    }

    @Test fun checkerDownloadsAndAnnouncesReadyEpisodesAndReportsTheRest() = runTest {
        val store = store()
        listOf("ready", "failed", "slow", "stuck", "gone", "unreachable").forEach { store.addPending(PendingEpisode(it, "Title $it", now - if (it == "stuck") 7 * hour else 0)) }
        val downloaded = mutableListOf<String>()
        val notices = mutableMapOf<String, String>()
        val checker = BriefingChecker(store, { id ->
            when (id) {
                "gone" -> throw ApiException("not_found", "Not found", 404)
                "unreachable" -> throw IOException("offline")
                "ready" -> PodcastJob(id, "ready", 100.0, title = "T", kind = "chapter", style = "deep-dive", durationSec = 845)
                "failed" -> PodcastJob(id, "failed", title = "T", kind = "chapter", style = "deep-dive", error = "No engine available")
                else -> PodcastJob(id, "generating", 50.0, title = "T", kind = "chapter", style = "deep-dive")
            }
        }, { downloaded += it }, { episode, message -> notices[episode.id] = message }, { now })
        assertTrue(checker.run())
        assertEquals(listOf("ready"), downloaded)
        assertEquals("Ready to play · 14 min · saved for offline listening", notices["ready"])
        assertEquals("Couldn't be made: No engine available", notices["failed"])
        assertTrue(notices.getValue("stuck").startsWith("Still not finished"))
        assertEquals(setOf("ready", "failed", "stuck"), notices.keys)
        assertEquals(listOf("slow", "unreachable"), store.pending.first().map { it.id })
    }
}
