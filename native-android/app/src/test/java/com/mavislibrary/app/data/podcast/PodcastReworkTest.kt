package com.mavislibrary.app.data.podcast

import com.mavislibrary.app.data.api.ApiException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class PodcastReworkTest {
    private val request = PodcastRequest("topic", "Morning briefing", query = "News")
    private var now = 1_800_000_000_000L
    private fun repo(studio: ScriptedStudio, library: PodcastLibrary, account: String? = "user-1") = PodcastRepository(studio, library, "studio", { now }, { account })

    // --- refresh
    @Test fun refreshWritesTheLibraryOnceAndNotAtAllWhenNothingChanged() = runTest {
        val studio = ScriptedStudio(); val library = CountingLibrary()
        (1..40).forEach { studio.jobs["j$it"] = job("j$it") }
        val repo = repo(studio, library)
        assertEquals(40, repo.refresh().size)
        assertEquals(1, library.writes); assertEquals(40, library.library.value.size)
        assertTrue(library.library.value.all { it.accountId == "user-1" && it.studio == "studio" })
        repo.refresh(); assertEquals(1, library.writes)
        studio.jobs["j41"] = job("j41", "queued"); repo.refresh()
        assertEquals(2, library.writes); assertEquals(41, library.library.value.size)
    }
    @Test fun refreshKeepsTheTranscriptAndDoesNotUndoAFinishedPodcast() = runTest {
        val studio = ScriptedStudio(); val library = CountingLibrary(); val repo = repo(studio, library)
        studio.jobs["a"] = job("a", transcript = "Host A: Hello.")
        assertEquals("ready", repo.check("a").status)
        // The list never carries transcripts, and this answer was prepared before the podcast finished.
        studio.listed = { PodcastListing(listOf(job("a", "generating"))) }
        repo.refresh()
        val held = library.library.value.single().job
        assertEquals("ready", held.status); assertEquals("Host A: Hello.", held.transcript)
    }
    @Test fun podcastsMissingFromACompleteListAreRemovedOnlyWhenTheStudioConfirms() = runTest {
        val studio = ScriptedStudio(); val library = CountingLibrary(); val repo = repo(studio, library)
        library.save(SavedPodcast(job("gone", "writing"), "studio", accountId = "user-1"))
        library.save(SavedPodcast(job("missed", "writing"), "studio", accountId = "user-1"))
        library.save(SavedPodcast(job("offline-copy"), "studio", localFile = "private.mp3", accountId = "user-1"))
        library.save(SavedPodcast(job("someone-else", "writing"), "studio", accountId = "user-2"))
        library.save(SavedPodcast(job("unsure", "writing"), "studio", accountId = "user-1"))
        studio.jobs["listed"] = job("listed")
        studio.onGet = { id -> when (id) { "missed" -> job("missed", "ready"); "unsure" -> throw IOException("offline"); else -> throw ScriptedStudio.notFound() } }
        repo.refresh()
        val ids = library.library.value.associate { it.job.id to it.job.status }
        assertEquals(setOf("listed", "missed", "offline-copy", "someone-else", "unsure"), ids.keys)
        assertEquals("ready", ids["missed"])
        assertFalse("downloaded and other accounts' podcasts are never checked", studio.gets.any { it == "offline-copy" || it == "someone-else" })
    }
    @Test fun anIncompleteListRemovesNothingAndConfirmationsAreCapped() = runTest {
        val studio = ScriptedStudio(); val library = CountingLibrary(); val repo = repo(studio, library)
        (1..9).forEach { library.save(SavedPodcast(job("old$it", "writing"), "studio", accountId = "user-1")) }
        studio.listed = { PodcastListing(emptyList(), complete = false) }
        repo.refresh()
        assertEquals(9, library.library.value.size); assertTrue(studio.gets.isEmpty())
        studio.listed = { PodcastListing(emptyList()) }
        repo.refresh()
        assertEquals(5, studio.gets.size); assertEquals(4, library.library.value.size)
    }
    @Test fun aPodcastTheStudioNoLongerHasStopsBeingFollowed() = runTest {
        val studio = ScriptedStudio(); val library = CountingLibrary(); val repo = repo(studio, library)
        library.save(SavedPodcast(job("gone", "writing"), "studio", accountId = "user-1"))
        assertEquals(404, (runCatching { repo.check("gone") }.exceptionOrNull() as ApiException).status)
        assertTrue(library.library.value.isEmpty())
        library.save(SavedPodcast(job("gone2", "writing"), "studio", accountId = "user-1"))
        assertEquals(404, (runCatching { repo.poll("gone2").toList() }.exceptionOrNull() as ApiException).status)
        assertEquals(0L, testScheduler.currentTime)
        assertTrue(library.library.value.isEmpty())
        library.save(SavedPodcast(job("kept"), "studio", localFile = "private.mp3", accountId = "user-1"))
        runCatching { repo.check("kept") }
        assertEquals("kept", library.library.value.single().job.id)
    }

    // --- creation
    @Test fun aNewPodcastIsDatedSoItSortsFirst() = runTest {
        val studio = ScriptedStudio(); val library = CountingLibrary()
        val saved = repo(studio, library).create(request)
        assertEquals("2027-01-15T08:00:00Z", saved.job.createdAt)
        assertEquals(saved, library.library.value.single())
    }
    @Test fun whenTheReplyIsLostThePodcastAlreadyMadeIsFoundInsteadOfMakingAnother() = runTest {
        val studio = ScriptedStudio(); val library = CountingLibrary(); val repo = repo(studio, library)
        studio.jobs["older"] = job("older", title = "Morning briefing", createdAt = "2027-01-14T08:00:00Z")
        repo.refresh()
        studio.onCreate = { studio.jobs["made"] = job("made", "queued", "Morning briefing", "2027-01-15T08:00:01Z"); throw IOException("connection reset") }
        val saved = repo.create(request)
        assertEquals("made", saved.job.id); assertEquals(request, saved.request)
        assertEquals(1, studio.creates.size)
        assertEquals(setOf("older", "made"), library.library.value.map { it.job.id }.toSet())
    }
    @Test fun aSecondTapAfterALostReplyFindsTheFirstPodcast() = runTest {
        val studio = ScriptedStudio(); val library = CountingLibrary(); val repo = repo(studio, library)
        // The reply is lost and so is the list: the phone is offline for a moment.
        studio.onCreate = { studio.jobs["made"] = job("made", "queued", "Morning briefing", "2027-01-15T08:00:01Z"); throw IOException("connection reset") }
        studio.listed = { throw IOException("offline") }
        assertTrue(runCatching { repo.create(request) }.exceptionOrNull() is IOException)
        // Still offline: nothing is sent blind.
        assertTrue(runCatching { repo.create(request) }.exceptionOrNull() is IOException)
        assertEquals(1, studio.creates.size)
        studio.listed = null; now += 60_000
        assertEquals("made", repo.create(request).job.id)
        assertEquals(1, studio.creates.size)
        // A later, deliberate request for the same thing is a new podcast.
        studio.onCreate = { PodcastCreated("second", "queued") }
        assertEquals("second", repo.create(request).job.id)
        assertEquals(2, studio.creates.size)
    }
    @Test fun aLostReplyWithNothingMadeLetsTheNextTapSendAgain() = runTest {
        val studio = ScriptedStudio(); val library = CountingLibrary(); val repo = repo(studio, library)
        studio.jobs["unrelated"] = job("unrelated", title = "Something else", createdAt = "2027-01-15T08:00:01Z")
        studio.jobs["stale"] = job("stale", title = "Morning briefing", createdAt = "2027-01-15T07:00:00Z")
        studio.onCreate = { throw IOException("no route to host") }
        assertTrue(runCatching { repo.create(request) }.exceptionOrNull() is IOException)
        studio.onCreate = { PodcastCreated("fresh", "queued") }
        assertEquals("fresh", repo.create(request).job.id)
        assertEquals(2, studio.creates.size)
    }
    @Test fun definiteRefusalsAreNotTreatedAsLostReplies() = runTest {
        val studio = ScriptedStudio(); val repo = repo(studio, CountingLibrary())
        studio.onCreate = { throw ApiException("daily_limit", "Daily limit reached", 429) }
        assertEquals(429, (runCatching { repo.create(request) }.exceptionOrNull() as ApiException).status)
        assertEquals(0, studio.lists)
        assertTrue(runCatching { repo.create(PodcastRequest("topic", "", query = "x")) }.exceptionOrNull() is IllegalArgumentException)
        assertEquals(1, studio.creates.size)
    }
    @Test fun whenOnlyThePhonesOwnWriteFailsThePodcastStillCountsAsMadeAndIsWrittenLater() = runTest {
        val studio = ScriptedStudio(); val library = CountingLibrary().apply { failSaves = 1 }; val repo = repo(studio, library)
        val saved = repo.create(request)
        assertEquals("new1", saved.job.id); assertTrue(library.library.value.isEmpty())
        studio.jobs["new1"] = job("new1", "writing", "Morning briefing", "2027-01-15T08:00:02Z")
        repo.refresh()
        val held = library.library.value.single()
        assertEquals("writing", held.job.status); assertEquals(request, held.request)
        assertEquals(1, studio.creates.size)
    }

    // --- try again
    @Test fun tryAgainReplacesTheFailedPodcast() = runTest {
        val studio = ScriptedStudio(); val library = CountingLibrary(); val repo = repo(studio, library)
        studio.jobs["bad"] = job("bad", "failed")
        val failed = SavedPodcast(job("bad", "failed"), "studio", request = request, accountId = "user-1").also { library.save(it) }
        val fresh = repo.retry(failed)
        assertEquals("new1", fresh.job.id)
        assertEquals(listOf("bad"), studio.deletes)
        assertEquals(listOf("new1"), library.library.value.map { it.job.id })
        repo.refresh()
        assertFalse(library.library.value.any { it.job.id == "bad" })
    }
    @Test fun tryAgainSendsNothingWhenThePodcastIsNotActuallyFailed() = runTest {
        val studio = ScriptedStudio(); val library = CountingLibrary(); val repo = repo(studio, library)
        studio.jobs["fine"] = job("fine", "ready")
        val stale = SavedPodcast(job("fine", "failed"), "studio", request = request, accountId = "user-1").also { library.save(it) }
        val result = repo.retry(stale)
        assertEquals("fine", result.job.id); assertEquals("ready", result.job.status)
        assertTrue(studio.creates.isEmpty()); assertTrue(studio.deletes.isEmpty())
    }
    @Test fun tryAgainNeedsAnAnswerAboutTheOldOneFirstAndKeepsItIfDeletionFails() = runTest {
        val studio = ScriptedStudio(); val library = CountingLibrary(); val repo = repo(studio, library)
        val failed = SavedPodcast(job("bad", "failed"), "studio", request = request, accountId = "user-1").also { library.save(it) }
        studio.onGet = { throw IOException("offline") }
        assertTrue(runCatching { repo.retry(failed) }.exceptionOrNull() is IOException)
        assertTrue(studio.creates.isEmpty())
        studio.onGet = null; studio.jobs["bad"] = job("bad", "failed")
        studio.onDelete = { throw ApiException("studio_unavailable", "Studio unavailable, try later", 502) }
        assertEquals("new1", repo.retry(failed).job.id)
        assertEquals(setOf("bad", "new1"), library.library.value.map { it.job.id }.toSet())
        assertTrue(runCatching { repo.retry(failed.copy(request = null)) }.exceptionOrNull() is IllegalStateException)
        // The studio has already forgotten the failed one: a new podcast is still made, and nothing is deleted.
        val forgotten = SavedPodcast(job("lost", "failed"), "studio", request = request, accountId = "user-1").also { library.save(it) }
        val before = studio.deletes.size
        assertEquals("new2", repo.retry(forgotten).job.id)
        assertEquals(before, studio.deletes.size)
        assertFalse(library.library.value.any { it.job.id == "lost" })
    }
}
