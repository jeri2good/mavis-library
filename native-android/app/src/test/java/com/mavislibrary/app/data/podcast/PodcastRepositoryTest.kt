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
    @Test fun singleCheckSavesTheJobButNotAfterDeletion() = runTest {
        val api = FakeStudio(podcastFixtures()); val cache = MemoryPodcastLibrary()
        val repo = PodcastRepository(api, cache, "studio") { "user-1" }
        assertEquals("queued", repo.check("job1").status)
        assertEquals("user-1", cache.library.value.single().accountId)
        repo.delete("job1"); repo.check("job1")
        assertTrue(cache.library.value.isEmpty())
        assertTrue(runCatching { repo.check("../job") }.isFailure)
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
    @Test fun coldStartCreateListAndJobRetryAtFiveSecondIntervals() = runTest {
        for (operation in listOf("create", "list", "job")) {
            val start = testScheduler.currentTime
            val times = mutableListOf<Long>()
            val service = object : PodcastService by FakeStudio(listOf(podcastFixtures()[4])) {
                fun attempt() {
                    times += testScheduler.currentTime - start
                    if (times.size <= 2) throw com.mavislibrary.app.data.api.ApiException("studio_unavailable", "Studio unavailable, try later", 502)
                }
                override suspend fun create(request: PodcastRequest): PodcastCreated { attempt(); return PodcastCreated("job1", "queued") }
                override suspend fun list(): List<PodcastJob> { attempt(); return listOf(podcastFixtures()[4]) }
                override suspend fun get(id: String): PodcastJob { attempt(); return podcastFixtures()[4].copy(id = id) }
            }
            val repo = PodcastRepository(service, MemoryPodcastLibrary(), "studio")
            val waking = mutableListOf<Boolean>()
            val observer = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { repo.wakingStudio.toList(waking) }
            val task = launch {
                when (operation) {
                    "create" -> repo.create(PodcastRequest("topic", "Topic", query = "Space"))
                    "list" -> repo.refresh()
                    else -> repo.poll("job1").toList()
                }
            }
            runCurrent()
            assertEquals(listOf(0L), times)
            assertEquals(listOf(false, true), waking)
            advanceTimeBy(4_999); runCurrent(); assertEquals(1, times.size)
            advanceTimeBy(1); runCurrent(); assertEquals(listOf(0L, 5_000L), times)
            advanceTimeBy(5_000); runCurrent(); task.join()
            assertEquals(listOf(0L, 5_000L, 10_000L), times)
            assertEquals(listOf(false, true, false), waking)
            observer.cancel()
        }
    }
    @Test fun coldStartStopsAfterTwoRetriesIncludingJobPolling() = runTest {
        for (operation in listOf("create", "list", "job")) {
            var attempts = 0
            val error = com.mavislibrary.app.data.api.ApiException("studio_unavailable", "Studio unavailable, try later", 502)
            val service = object : PodcastService by FakeStudio(listOf(podcastFixtures()[4])) {
                override suspend fun create(request: PodcastRequest): PodcastCreated { attempts++; throw error }
                override suspend fun list(): List<PodcastJob> { attempts++; throw error }
                override suspend fun get(id: String): PodcastJob { attempts++; throw error }
            }
            val repo = PodcastRepository(service, MemoryPodcastLibrary(), "studio")
            val start = testScheduler.currentTime
            val result = runCatching {
                when (operation) {
                    "create" -> repo.create(PodcastRequest("topic", "Topic", query = "Space"))
                    "list" -> repo.refresh()
                    else -> repo.poll("job1").toList()
                }
            }
            assertSame(error, result.exceptionOrNull())
            assertEquals(3, attempts)
            assertEquals(10_000L, testScheduler.currentTime - start)
            assertFalse(repo.wakingStudio.first())
        }
    }
    @Test fun otherErrorsAndDeleteNeverTriggerColdStartRetries() = runTest {
        for (error in listOf(
            com.mavislibrary.app.data.api.ApiException("studio_bad_response", "Bad response", 502),
            com.mavislibrary.app.data.api.ApiException("studio_unavailable", "Unavailable", 503),
            com.mavislibrary.app.data.api.ApiException("daily_limit", "Daily limit reached", 429),
            IOException("offline"),
        )) {
            var attempts = 0
            val service = object : PodcastService by FakeStudio(listOf(podcastFixtures()[4])) {
                override suspend fun list(): List<PodcastJob> { attempts++; throw error }
            }
            val repo = PodcastRepository(service, MemoryPodcastLibrary(), "studio")
            assertSame(error, runCatching { repo.refresh() }.exceptionOrNull())
            assertEquals(1, attempts)
            assertFalse(repo.wakingStudio.first())
        }
        var deletes = 0
        val service = object : PodcastService by FakeStudio(listOf(podcastFixtures()[4])) {
            override suspend fun delete(id: String) { deletes++; throw com.mavislibrary.app.data.api.ApiException("studio_unavailable", "Unavailable", 502) }
        }
        assertTrue(runCatching { PodcastRepository(service, MemoryPodcastLibrary(), "studio").delete("job1") }.isFailure)
        assertEquals(1, deletes)
        assertEquals(0L, testScheduler.currentTime)
    }
    @Test fun cancellationDuringStudioWakeStopsRetriesAndClearsBanner() = runTest {
        var attempts = 0
        val service = object : PodcastService by FakeStudio(listOf(podcastFixtures()[4])) {
            override suspend fun list(): List<PodcastJob> { attempts++; throw com.mavislibrary.app.data.api.ApiException("studio_unavailable", "Unavailable", 502) }
        }
        val repo = PodcastRepository(service, MemoryPodcastLibrary(), "studio")
        val task = launch { repo.refresh() }
        runCurrent(); assertTrue(repo.wakingStudio.first())
        task.cancelAndJoin(); advanceTimeBy(10_000); runCurrent()
        assertEquals(1, attempts)
        assertFalse(repo.wakingStudio.first())
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
