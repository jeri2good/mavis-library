package com.mavislibrary.app.audio

import com.mavislibrary.app.data.api.LibriVoxSection
import com.mavislibrary.app.data.api.LibriVoxVersion
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException

class AudioFileStoreTest {
    @get:Rule val folder = TemporaryFolder()
    private val server = MockWebServer()
    private val sleeps = mutableListOf<Long>()
    private val client = OkHttpClient.Builder().retryOnConnectionFailure(false).build()

    @Before fun start() = server.start()
    @After fun stop() = server.shutdown()

    private fun version(chapters: Int = 1) = LibriVoxVersion(
        id = "book",
        sections = (1..chapters).map { LibriVoxSection(n = it, url = server.url("/$it.mp3").toString()) },
    )
    private fun store(http: OkHttpClient = client) = AudioFileStore(folder.root, http) { sleeps += it }
    private fun chapter(index: Int) = folder.root.resolve("audio/book/$index.mp3")
    private fun reply(status: Int, body: String = "audio") = server.enqueue(MockResponse().setResponseCode(status).setBody(body))
    private suspend fun failure(store: AudioFileStore, version: LibriVoxVersion): IOException {
        try {
            store.download(version)
            fail("Expected download failure")
        } catch (error: IOException) {
            return error
        }
        throw AssertionError("Expected IOException")
    }

    @Test fun `500 then 200 succeeds`() = runTest {
        reply(500)
        reply(200, "chapter one")
        store().download(version())
        assertEquals(2, server.requestCount)
        assertEquals(listOf(1_000L), sleeps)
        assertEquals("chapter one", chapter(0).readText())
    }

    @Test fun `four 500s fail with chapter message and keep earlier chapters`() = runTest {
        reply(200, "saved chapter")
        repeat(4) { reply(500) }
        val progress = mutableListOf<Pair<Int, Int>>()
        val store = store()
        val error = try {
            store.download(version(2)) { done, total -> progress += done to total }
            throw AssertionError("Expected IOException")
        } catch (error: IOException) { error }
        assertEquals("Chapter 2 of 2 failed (HTTP 500). Tap Save offline to continue - the chapters already saved are kept.", error.message)
        assertEquals(5, server.requestCount)
        assertEquals(listOf(1_000L, 3_000L, 8_000L), sleeps)
        assertEquals("saved chapter", chapter(0).readText())
        assertFalse(chapter(1).exists())
        assertEquals(listOf(1 to 2), progress)

        reply(200, "resumed chapter")
        store.download(version(2))
        assertEquals(6, server.requestCount)
        assertEquals("resumed chapter", chapter(1).readText())
    }

    @Test fun `404 fails after one attempt`() = runTest {
        reply(404)
        val error = failure(store(), version())
        assertEquals("Chapter 1 of 1 failed (HTTP 404). Tap Save offline to continue - the chapters already saved are kept.", error.message)
        assertEquals(1, server.requestCount)
        assertTrue(sleeps.isEmpty())
    }

    @Test fun `IOException then success removes stale part before each attempt`() = runTest {
        val part = chapter(0).resolveSibling("0.mp3.part")
        part.parentFile.mkdirs()
        part.writeText("stale")
        var attempts = 0
        val http = client.newBuilder().addInterceptor { chain ->
            assertFalse("Partial file must be removed before every attempt", part.exists())
            if (++attempts == 1) {
                part.writeText("interrupted chapter")
                throw IOException("connection reset")
            }
            chain.proceed(chain.request())
        }.build()
        reply(200, "complete")
        store(http).download(version())
        assertEquals(2, attempts)
        assertEquals(listOf(1_000L), sleeps)
        assertEquals("complete", chapter(0).readText())
        assertFalse(part.exists())
    }

    @Test fun `completed chapters are not downloaded on second call`() = runTest {
        reply(200, "one")
        reply(200, "two")
        val store = store()
        val version = version(2)
        store.download(version)
        store.download(version)
        assertEquals(2, server.requestCount)
        assertTrue(store.isComplete(version))
        assertEquals("one", chapter(0).readText())
        assertEquals("two", chapter(1).readText())
    }

    @Test fun `429 retries successfully`() = runTest {
        reply(429)
        reply(200)
        store().download(version())
        assertEquals(2, server.requestCount)
        assertEquals(listOf(1_000L), sleeps)
    }

    @Test fun `IOException stops after four attempts`() = runTest {
        var attempts = 0
        val http = client.newBuilder().addInterceptor {
            attempts++
            throw IOException("timeout")
        }.build()
        val error = failure(store(http), version())
        assertEquals(4, attempts)
        assertEquals(listOf(1_000L, 3_000L, 8_000L), sleeps)
        assertEquals("Chapter 1 of 1 failed (I/O error). Tap Save offline to continue - the chapters already saved are kept.", error.message)
        assertFalse(chapter(0).exists())
    }
}
