package com.mavislibrary.app.audio

import android.net.Uri
import androidx.media3.datasource.DataSpec
import com.mavislibrary.app.data.podcast.*
import kotlinx.coroutines.test.runTest
import okhttp3.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class PodcastAudioTest {
    @get:Rule val tmp = TemporaryFolder()
    @Test fun gradientArtworkIsGeneratedOnDeviceAndResumeKeysDoNotCollide() {
        val bytes = PodcastArtwork.create("A podcast about Android")
        val bitmap = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        assertEquals(512, bitmap.width); assertEquals(512, bitmap.height)
        val first = SavedPodcast(podcastFixtures()[4], "https://first.example.org")
        val second = first.copy(studio = "https://second.example.org")
        assertNotEquals(first.resumeKey, second.resumeKey)
        assertTrue(first.resumeKey.startsWith("podcast:"))
    }
    @Test fun datasourceResolvesSignedAudioWithoutLeakingAccountHeaders() {
        val seen = mutableListOf<Request>()
        val api = PodcastApi("https://studio.example.org", execute = { request ->
            seen += request; fakeSignedAudio(request) ?: fakeResponse(request, "456789", 206, "audio/mpeg", Headers.headersOf("Content-Range", "bytes 4-9/10"))
        }, token = { "fixture-key" })
        val source = PodcastDataSource(api)
        assertEquals(6L, source.open(DataSpec.Builder().setUri(Uri.parse(api.audioUrl("job1"))).setPosition(4).build()))
        val bytes = ByteArray(6); assertEquals(6, source.read(bytes, 0, 6)); assertEquals("456789", bytes.decodeToString())
        assertEquals(-1, source.read(bytes, 0, 6)); source.close()
        assertEquals("bytes=4-", seen.last().header("Range")); assertNull(seen.last().header("Authorization"))
        assertEquals("Bearer fixture-key", seen.first().header("Authorization"))
        assertNull(seen.last().header("x-mavis-access"))
        assertTrue(runCatching { source.open(DataSpec(Uri.parse("https://foreign.example.org/v1/podcasts/job1/audio"))) }.isFailure)
        assertEquals(2, seen.size)
    }
    @Test fun datasourceDoesNotFollowCrossHostRedirect() {
        val seen = mutableListOf<Request>()
        val api = PodcastApi("https://studio.example.org", execute = { request -> seen += request; fakeSignedAudio(request) ?: fakeResponse(request, "", 302, headers = Headers.headersOf("Location", "https://foreign.example.org/v1/podcasts/job1/audio")) }, token = { "fixture-key" })
        assertTrue(runCatching { PodcastDataSource(api).open(DataSpec(Uri.parse(api.audioUrl("job1")))) }.isFailure)
        assertEquals(2, seen.size); assertEquals("audio.example.org", seen.last().url.host)
        assertNull(seen.last().header("Authorization"))
    }
    @Test fun ignoredRangeIsSkippedAndInvalidRangeIsRejected() {
        val api = PodcastApi("https://studio.example.org", execute = { fakeSignedAudio(it) ?: fakeResponse(it, "0123456789", type = "audio/mpeg") }, token = { "fixture-key" })
        val source = PodcastDataSource(api)
        assertEquals(6L, source.open(DataSpec.Builder().setUri(api.audioUrl("job1")).setPosition(4).build()))
        val bytes = ByteArray(6); source.read(bytes, 0, 6); assertEquals("456789", bytes.decodeToString()); source.close()
        val invalid = PodcastApi("https://studio.example.org", execute = { fakeSignedAudio(it) ?: fakeResponse(it, "012345", 206, "audio/mpeg", Headers.headersOf("Content-Range", "bytes 0-5/10")) }, token = { "fixture" })
        assertTrue(runCatching { PodcastDataSource(invalid).open(DataSpec.Builder().setUri(invalid.audioUrl("job1")).setPosition(4).build()) }.isFailure)
    }
    @Test fun downloadedCopyIsPrivateCompletedAndSeparatedByStudio() = runTest {
        val api = PodcastApi("https://studio.example.org", execute = { fakeSignedAudio(it) ?: fakeResponse(it, "fake-mp3-bytes", type = "audio/mpeg") }, token = { "fixture-key" })
        val store = AudioFileStore(tmp.root, OkHttpClient())
        val file = store.downloadPodcast(api, "job1")
        assertTrue(file.canonicalPath.startsWith(tmp.root.canonicalPath + "/audio/podcasts/"))
        assertEquals("fake-mp3-bytes", file.readText()); assertEquals(file, store.podcastLocal(api.address, "job1"))
        assertNull(store.podcastLocal("https://other.example.org", "job1"))
        assertFalse(file.parentFile!!.listFiles()!!.any { it.name.endsWith(".part") })
    }
    @Test fun badDownloadNeverBecomesPlayableAndCleanupIsLocalFinishedOnly() = runTest {
        val store = AudioFileStore(tmp.root, OkHttpClient())
        val bad = PodcastApi("https://studio.example.org", execute = { fakeSignedAudio(it) ?: fakeResponse(it, "<html>not audio</html>", type = "text/html") }, token = { "fixture-key" })
        assertTrue(runCatching { store.downloadPodcast(bad, "job1") }.isFailure)
        assertNull(store.podcastLocal(bad.address, "job1"))
        val cache = MemoryPodcastLibrary()
        cache.save(SavedPodcast(podcastFixtures()[4].copy(id = "old", createdAt = "2026-08-01T00:00:00Z"), bad.address))
        cache.save(SavedPodcast(podcastFixtures().first().copy(id = "queued", createdAt = "2026-08-01T00:00:00Z"), bad.address))
        cache.save(SavedPodcast(podcastFixtures()[4].copy(id = "unknown", createdAt = ""), bad.address))
        assertEquals(1, store.cleanupPodcasts(cache, 30, Instant.parse("2026-10-12T00:00:00Z")))
        assertEquals(setOf("queued", "unknown"), cache.library.value.map { it.job.id }.toSet())
    }
    @Test fun playbackErrorsAreShownInFixedWordsOnly() {
        assertNull(playbackErrorMessage(null))
        val expired = RuntimeException("Source error", java.io.IOException("wrapped", PodcastAudioException(AudioProblem.EXPIRED)))
        assertEquals(AudioProblem.EXPIRED.message, playbackErrorMessage(expired))
        // The player service hands causes over as plain text; the wording alone identifies the problem.
        assertEquals(AudioProblem.NOT_READY.message, playbackErrorMessage(RuntimeException("Source error", android.os.RemoteException(AudioProblem.NOT_READY.message))))
        val other = playbackErrorMessage(RuntimeException("Source error", java.io.FileNotFoundException("/data/user/0/private/file.mp3 https://audio.example.org/x?sig=abc")))
        assertFalse(other!!.contains("/data")); assertFalse(other.contains("sig="))
    }
    @Test fun seekingPastTheEndIsReportedToThePlayerAsOutOfRangeAndWrongContentIsTyped() {
        val past = PodcastApi("https://studio.example.org", execute = { fakeSignedAudio(it) ?: fakeResponse(it, "", 416) }, token = { "fixture-key" })
        val error = runCatching { PodcastDataSource(past).open(DataSpec.Builder().setUri(past.audioUrl("job1")).setPosition(999).build()) }.exceptionOrNull()
        assertEquals(androidx.media3.common.PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE, (error as androidx.media3.datasource.DataSourceException).reason)
        val html = PodcastApi("https://studio.example.org", execute = { fakeSignedAudio(it) ?: fakeResponse(it, "<html>", type = "text/html") }, token = { "fixture-key" })
        val wrong = runCatching { PodcastDataSource(html).open(DataSpec(Uri.parse(html.audioUrl("job1")))) }.exceptionOrNull()
        assertEquals(AudioProblem.NOT_AUDIO, (wrong as PodcastAudioException).problem)
    }
}
