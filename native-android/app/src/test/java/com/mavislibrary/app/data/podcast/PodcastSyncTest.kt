package com.mavislibrary.app.data.podcast

import com.mavislibrary.app.data.api.ApiException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class PodcastSyncTest {
    private fun sync(studio: ScriptedStudio, library: CountingLibrary): PodcastSync {
        val repo = PodcastRepository(studio, library, "studio") { "user-1" }
        return PodcastSync(repo, library.library) { it.accountId == "user-1" }
    }

    @Test fun firstLoadThenConnectedThenAQuietListEveryTwoMinutes() = runTest {
        val studio = ScriptedStudio(); val library = CountingLibrary(); val sync = sync(studio, library)
        studio.jobs["a"] = job("a")
        assertTrue(sync.state.value.firstLoad)
        val running = launch { sync.run() }
        runCurrent()
        val state = sync.state.value
        assertFalse(state.loading); assertTrue(state.synced); assertNull(state.problem); assertNotNull(state.lastSyncMs)
        assertEquals(listOf("a"), library.library.value.map { it.job.id })
        advanceTimeBy(119_999); runCurrent(); assertEquals(1, studio.lists)
        advanceTimeBy(1); runCurrent(); assertEquals(2, studio.lists)
        // Nothing is being made, so no status checks at all.
        assertTrue(studio.gets.isEmpty())
        running.cancelAndJoin()
    }
    @Test fun aFailedListIsATypedProblemRetriedWithGrowingGapsAndCachedPodcastsStay() = runTest {
        val studio = ScriptedStudio(); val library = CountingLibrary(); val sync = sync(studio, library)
        library.save(SavedPodcast(job("cached"), "studio", accountId = "user-1"))
        studio.listed = { throw IOException("offline") }
        val running = launch { sync.run() }
        runCurrent()
        assertEquals(PodcastProblemKind.OFFLINE, sync.state.value.problem!!.kind)
        assertFalse(sync.state.value.synced); assertFalse(sync.state.value.firstLoad)
        assertEquals("cached", library.library.value.single().job.id)
        advanceTimeBy(15_000); runCurrent(); assertEquals(2, studio.lists)
        advanceTimeBy(30_000); runCurrent(); assertEquals(3, studio.lists)
        advanceTimeBy(59_999); runCurrent(); assertEquals(3, studio.lists)
        studio.listed = null
        advanceTimeBy(1); runCurrent()
        assertEquals(4, studio.lists); assertTrue(sync.state.value.synced); assertNull(sync.state.value.problem)
        running.cancelAndJoin()
    }
    @Test fun refreshNowReadsTheListAtOnceWithoutStoppingAnything() = runTest {
        val studio = ScriptedStudio(); val library = CountingLibrary(); val sync = sync(studio, library)
        studio.jobs["making"] = job("making", "writing")
        val running = launch { sync.run() }
        runCurrent()
        val checksBefore = studio.gets.size
        sync.refreshNow(); runCurrent()
        assertEquals(2, studio.lists)
        assertEquals(checksBefore + 1, studio.gets.size)
        advanceTimeBy(4_000); runCurrent()
        assertEquals(checksBefore + 2, studio.gets.size)
        running.cancelAndJoin()
    }
    @Test fun podcastsBeingMadeAreFollowedToTheEndWithinTheRequestBudget() = runTest {
        val studio = ScriptedStudio(); val library = CountingLibrary(); val sync = sync(studio, library)
        listOf("a", "b", "c").forEach { studio.jobs[it] = job(it, "generating") }
        val running = launch { sync.run() }
        advanceTimeBy(60_000); runCurrent()
        // Three podcasts used to mean 60 status requests a minute plus lists, which is the gateway's whole allowance.
        assertTrue("checks in the first minute: ${studio.gets.size}", studio.gets.size <= PodcastSync.CHECKS_PER_MINUTE + 3)
        assertEquals(1, studio.lists)
        studio.jobs["a"] = job("a", "ready"); studio.jobs["b"] = job("b", "failed")
        advanceTimeBy(10_000); runCurrent()
        assertEquals(mapOf("a" to "ready", "b" to "failed", "c" to "generating"), library.library.value.associate { it.job.id to it.job.status })
        val settled = studio.gets.size
        advanceTimeBy(20_000); runCurrent()
        assertTrue(studio.gets.drop(settled).all { it == "c" })
        studio.jobs["c"] = job("c", "ready")
        advanceTimeBy(10_000); runCurrent()
        val done = studio.gets.size
        advanceTimeBy(300_000); runCurrent()
        assertEquals(done, studio.gets.size)
        running.cancelAndJoin()
    }
    @Test fun beingToldToSlowDownPausesFollowingForAMinuteInsteadOfEndingIt() = runTest {
        val studio = ScriptedStudio(); val library = CountingLibrary(); val sync = sync(studio, library)
        studio.jobs["a"] = job("a", "writing"); studio.jobs["b"] = job("b", "writing")
        val running = launch { sync.run() }
        runCurrent()
        studio.onGet = { throw ApiException("rate_limited", "Too many requests just now.", 429) }
        val before = studio.gets.size
        advanceTimeBy(5_000); runCurrent()
        // The round stops at the first refusal rather than spending another request on the second podcast.
        assertEquals(before + 1, studio.gets.size)
        assertEquals(PodcastProblemKind.SLOW_DOWN, sync.state.value.tracking.values.single().kind)
        advanceTimeBy(59_000); runCurrent(); assertEquals(before + 1, studio.gets.size)
        studio.onGet = null; studio.jobs["a"] = job("a", "ready")
        advanceTimeBy(1_000); runCurrent()
        assertEquals("ready", library.library.value.first { it.job.id == "a" }.job.status)
        assertTrue(sync.state.value.tracking.isEmpty())
        running.cancelAndJoin()
    }
    @Test fun aPodcastMadeWhileTheScreenIsOpenIsPickedUpAndAVanishedOneIsDropped() = runTest {
        val studio = ScriptedStudio(); val library = CountingLibrary(); val sync = sync(studio, library)
        val repo = PodcastRepository(studio, library, "studio") { "user-1" }
        val running = launch { sync.run() }
        runCurrent(); assertTrue(studio.gets.isEmpty())
        val made = repo.create(PodcastRequest("topic", "Topic", query = "Space"))
        studio.jobs[made.job.id] = job(made.job.id, "sourcing")
        runCurrent()
        assertEquals(listOf(made.job.id), studio.gets)
        assertEquals("sourcing", library.library.value.single().job.status)
        studio.jobs.clear()
        advanceTimeBy(4_000); runCurrent()
        assertTrue(library.library.value.isEmpty()); assertTrue(sync.state.value.tracking.isEmpty())
        val after = studio.gets.size
        advanceTimeBy(60_000); runCurrent(); assertEquals(after, studio.gets.size)
        running.cancelAndJoin()
    }
    @Test fun waitsGrowWithAgeCountAndTrouble() {
        assertEquals(4_000L, PodcastSync.trackWait(1, 0, null, 0))
        assertEquals(8_000L, PodcastSync.trackWait(1, 3 * 60_000L, null, 0))
        assertEquals(15_000L, PodcastSync.trackWait(1, 12 * 60_000L, null, 0))
        assertEquals(60_000L, PodcastSync.trackWait(1, 45 * 60_000L, null, 0))
        assertEquals(10_000L, PodcastSync.trackWait(4, 0, null, 0))
        (1..10).forEach { n -> assertTrue(n * 60_000L / PodcastSync.trackWait(n, 0, null, 0) <= PodcastSync.CHECKS_PER_MINUTE) }
        val offline = PodcastProblem(PodcastProblemKind.OFFLINE, "offline")
        assertEquals(listOf(8_000L, 16_000L, 32_000L, 60_000L, 60_000L), (1..5).map { PodcastSync.trackWait(1, 0, offline, it) })
        assertEquals(90_000L, PodcastSync.trackWait(1, 0, PodcastProblem(PodcastProblemKind.STUDIO_BUSY, "busy", 90), 1))
        assertEquals(300_000L, PodcastSync.trackWait(1, 0, PodcastProblem(PodcastProblemKind.SIGN_IN, "sign in"), 1))
        assertEquals(120_000L, PodcastSync.listWait(null, 0))
        assertEquals(listOf(15_000L, 30_000L, 60_000L, 120_000L, 240_000L, 300_000L, 300_000L), (1..7).map { PodcastSync.listWait(offline, it) })
        assertEquals(60_000L, PodcastSync.listWait(PodcastProblem(PodcastProblemKind.SLOW_DOWN, "slow"), 1))
        assertEquals(300_000L, PodcastSync.listWait(PodcastProblem(PodcastProblemKind.ACCESS, "access"), 1))
    }
}
