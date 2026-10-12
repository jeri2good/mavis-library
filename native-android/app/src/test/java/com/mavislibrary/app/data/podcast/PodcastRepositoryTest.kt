package com.mavislibrary.app.data.podcast

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class MemoryPodcastLibrary : PodcastLibrary {
    override val library = MutableStateFlow<List<SavedPodcast>>(emptyList())
    override suspend fun save(podcast: SavedPodcast) {
        val old = library.value.find { it.key == podcast.key }
        library.value = listOf(podcast.copy(request = podcast.request ?: old?.request, localFile = podcast.localFile ?: old?.localFile)) + library.value.filterNot { it.key == podcast.key }
    }
    override suspend fun remove(id: String, studio: String) { library.value = library.value.filterNot { it.job.id == id && it.studio == studio } }
}
private class FakeStudio(private val jobs: List<PodcastJob>) : PodcastService {
    var calls = 0
    var deleted = false
    var offlineCalls = 0
    override suspend fun health() = StudioHealth(true, StudioEngines(StudioEngine(false), StudioEngine(true)))
    override suspend fun create(request: PodcastRequest) = PodcastCreated("job1", "queued")
    override suspend fun get(id: String): PodcastJob {
        calls++
        if (offlineCalls-- > 0) throw IOException("offline")
        return jobs[(calls - 1).coerceAtMost(jobs.lastIndex)]
    }
    override suspend fun list() = listOf(jobs.last())
    override suspend fun delete(id: String) { deleted = true }
}
@OptIn(ExperimentalCoroutinesApi::class)
class PodcastRepositoryTest {
    @Test fun queuedToReadyWithThreeSecondVirtualTimeAndOfflineCache() = runTest {
        val service = FakeStudio(podcastFixtures().take(5)); val cache = MemoryPodcastLibrary()
        val repo = PodcastRepository(service, cache, "https://studio.example.org")
        repo.create(PodcastRequest("topic", "Topic", query = "Space"))
        val events = mutableListOf<PodcastPoll>()
        val poll = launch { repo.poll("job1").toList(events) }
        runCurrent(); assertEquals(1, service.calls)
        advanceTimeBy(2_999); runCurrent(); assertEquals(1, service.calls)
        advanceUntilIdle(); poll.join()
        assertEquals(12_000, testScheduler.currentTime)
        assertEquals(listOf("queued", "sourcing", "writing", "generating", "ready"), events.filterIsInstance<PodcastPoll.Updated>().map { it.job.status })
        assertEquals("ready", cache.library.value.single().job.status)
        assertEquals("Space", cache.library.value.single().request!!.query)
    }
    @Test fun failureIsTerminal() = runTest {
        val api = FakeStudio(listOf(podcastFixtures().last())); val cache = MemoryPodcastLibrary()
        val events = PodcastRepository(api, cache, "studio").poll("job1").toList()
        assertEquals(1, api.calls); assertEquals("failed", (events.single() as PodcastPoll.Updated).job.status)
    }
    @Test fun cancellationStopsPollingButDoesNotPretendServerCancelled() = runTest {
        val api = FakeStudio(listOf(podcastFixtures().first())); val repo = PodcastRepository(api, MemoryPodcastLibrary(), "studio")
        val poll = launch { repo.poll("job1").collect() }
        runCurrent(); poll.cancelAndJoin(); advanceTimeBy(60_000); runCurrent()
        assertEquals(1, api.calls); assertFalse(api.deleted)
        repo.delete("job1"); assertTrue(api.deleted)
    }
    @Test fun timeoutDoesNotMarkServerJobFailed() = runTest {
        val api = FakeStudio(listOf(podcastFixtures().first())); val cache = MemoryPodcastLibrary()
        val events = PodcastRepository(api, cache, "studio").poll("job1", 9_000).toList()
        assertEquals(PodcastPoll.TimedOut, events.last()); assertEquals(9_000, testScheduler.currentTime)
        assertEquals("queued", cache.library.value.single().job.status)
    }
    @Test fun defaultWaitEndsAtExactlyTwentyFiveMinutesAndCanResumeAfterScreenReentry() = runTest {
        val api = FakeStudio(listOf(podcastFixtures().first())); val repo = PodcastRepository(api, MemoryPodcastLibrary(), "studio")
        assertEquals(PodcastPoll.TimedOut, repo.poll("job1").toList().last())
        assertEquals(1_500_000L, testScheduler.currentTime)
        assertEquals(500, api.calls)
        val resumed = launch { repo.poll("job1").collect() }
        runCurrent(); assertEquals(501, api.calls); resumed.cancelAndJoin()
    }
    @Test fun networkFailuresBackoffAndRecover() = runTest {
        val api = FakeStudio(listOf(podcastFixtures()[4])).apply { offlineCalls = 3 }
        val events = PodcastRepository(api, MemoryPodcastLibrary(), "studio").poll("job1").toList()
        assertEquals(21_000, testScheduler.currentTime)
        assertEquals(3, events.count { it == PodcastPoll.Unreachable }); assertEquals("ready", (events.last() as PodcastPoll.Updated).job.status)
    }
    @Test fun reconnectListPreservesLocalCopyAndRetrySource() = runTest {
        val cache = MemoryPodcastLibrary(); val api = FakeStudio(listOf(podcastFixtures()[4])); val repo = PodcastRepository(api, cache, "studio")
        cache.save(SavedPodcast(podcastFixtures().first(), "studio", "private.mp3", PodcastRequest("topic", "Topic", query = "Space")))
        repo.refresh()
        assertEquals("private.mp3", cache.library.value.single().localFile)
        assertEquals("Space", cache.library.value.single().request!!.query)
        repo.delete("job1"); assertTrue(cache.library.value.isEmpty())
        repo.refresh(); assertTrue(cache.library.value.isEmpty())
    }
}
