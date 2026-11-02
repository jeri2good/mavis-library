package com.mavislibrary.app.data.kids

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.*
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.rules.TemporaryFolder
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class StoryPackTest {
    @get:Rule val tmp = TemporaryFolder()
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private fun resource(path: String) = javaClass.getResourceAsStream("/fixtures/kids/$path")!!.use { it.readBytes() }
    private fun fixture(): File = tmp.newFile("story.zip").also { file ->
        ZipOutputStream(file.outputStream()).use { zip ->
            listOf("story.json", "img/01.jpg", "music.mp3", "audio/host/t2/01.mp3").forEach {
                zip.putNextEntry(ZipEntry(it)); zip.write(resource(it)); zip.closeEntry()
            }
        }
    }
    private fun digest(file: File) = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }
    private fun catalog(zip: File) = """{"version":1,"credits":"Verbatim credits https://example.test",
        "voices":[{"id":"host","label":"Cheerful host","default":true}],
        "tiers":[{"id":"t2","label":"Ages 5-7","minAge":5,"maxAge":7}],
        "stories":[{"id":"creation","title":"God Makes Everything","ref":"Genesis 1-2","obs":[1,2],
        "cover":"cover-creation.jpg","zip":"story-creation.zip","bytes":${zip.length()},"sha256":"${digest(zip)}","minutes":{"t2":4}}]}"""

    @Test fun ageBoundariesAndTierPageFiltering() {
        (2..10).forEach { assertEquals(if (it < 5) "t1" else if (it < 8) "t2" else "t3", storyTier(it)) }
        val story = StoryPacks.story(resource("story.json").toString(Charsets.UTF_8))
        assertEquals(1, story.pagesFor("t1").size)
        assertEquals(2, story.pagesFor("t2").size)
        assertEquals(2, story.pagesFor("t3").size)
        assertTrue(story.pagesFor("missing").isEmpty())
        assertEquals("Light", story.question["t3"]!!.choices[0])
    }
    @Test fun parseCatalogAndCompleteLocalPack() {
        val zip = fixture()
        val parsed = StoryPacks.catalog(catalog(zip))
        assertEquals(4, parsed.stories.single().minutes["t2"])
        assertEquals("Verbatim credits https://example.test", parsed.credits)
        StoryRepository.verify(zip, parsed.stories.single().sha256)
        val dir = tmp.newFolder()
        StoryRepository.extract(zip, dir)
        val story = StoryPacks.story(File(dir, "story.json").readText())
        StoryRepository.validateFiles(dir, story)
        assertEquals("creation", story.id)
        assertTrue(File(dir, "music.mp3").length() > 100)
    }
    @Test fun checksumMismatchRejected() {
        assertThrows(IllegalArgumentException::class.java) { StoryRepository.verify(fixture(), "0".repeat(64)) }
    }
    @Test fun zipSlipAndActivePathsRejected() {
        listOf("../escape", "/escape", "img/../../escape", "img\\escape", "https://evil/file").forEachIndexed { index, path ->
            val zip = tmp.newFile("bad-$index.zip")
            ZipOutputStream(zip.outputStream()).use { it.putNextEntry(ZipEntry(path)); it.write(1); it.closeEntry() }
            assertThrows(IllegalArgumentException::class.java) { StoryRepository.extract(zip, tmp.newFolder()) }
        }
        listOf("img/%2e%2e/file", "file.jpg?url=x", "file:///tmp/x", "img/./file").forEach {
            assertThrows(IllegalArgumentException::class.java) { StoryPacks.relative(it) }
        }
        assertFalse(File(tmp.root, "escape").exists())
    }
    @Test fun defaultsPersistAndParentUnlockDoesNotLeaveKidsMode() = runTest(UnconfinedTestDispatcher()) {
        val file = File(tmp.root, "kids.preferences_pb")
        val ds = PreferenceDataStoreFactory.create(scope = backgroundScope) { file }
        val prefs = KidsPrefs(ds)
        assertEquals(5, prefs.state.first().childAge); assertEquals("host", prefs.state.first().narrator)
        prefs.start("Sam", 15, "1234")
        prefs.storySettings(9, "grandma")
        assertEquals(PinResult.Correct, prefs.unlockParents("1234"))
        val reopened = KidsPrefs(ds).state.first()
        assertTrue(reopened.enabled); assertEquals(9, reopened.childAge); assertEquals("grandma", reopened.narrator)
        assertTrue(runCatching { prefs.storySettings(11, "host") }.exceptionOrNull() is IllegalArgumentException)
        assertTrue(runCatching { prefs.storySettings(5, "unknown") }.exceptionOrNull() is IllegalArgumentException)
    }
    @Test fun fakeHttpResumesAndWorksOfflineAfterInstall() = runTest {
        val zip = fixture()
        val server = MockWebServer()
        server.start()
        try {
            val repo = StoryRepository(context, server.url("/").toString(), OkHttpClient())
            repo.root.deleteRecursively(); repo.root.mkdirs()
            server.enqueue(MockResponse().setBody(catalog(zip)))
            val entry = repo.catalog().stories.single()
            val bytes = zip.readBytes()
            val offset = bytes.size / 2
            File(repo.root, "${entry.id}-${entry.sha256}.part").writeBytes(bytes.copyOfRange(0, offset))
            server.enqueue(MockResponse().setResponseCode(206).setHeader("Content-Range", "bytes $offset-${bytes.lastIndex}/${bytes.size}")
                .setBody(okio.Buffer().write(bytes, offset, bytes.size - offset)))
            val progress = mutableListOf<Float>()
            assertEquals("creation", repo.download(entry) { progress += it }.id)
            assertTrue(repo.installed(entry))
            assertEquals(1f, progress.last())
            server.takeRequest()
            assertEquals("bytes=$offset-", server.takeRequest().getHeader("Range"))
            assertNotNull(repo.cachedCatalog())
            assertEquals(2, repo.load(entry).pages.size)
            // An offline re-open must not issue another HTTP request.
            repo.download(entry) {}
            assertEquals(2, server.requestCount)
        } finally { server.shutdown() }
    }
    @Test fun serverIgnoringRangeRestartsInsteadOfAppending() = runTest {
        val zip = fixture()
        MockWebServer().use { server ->
            server.start()
            val repo = StoryRepository(context, server.url("/").toString())
            repo.root.deleteRecursively(); repo.root.mkdirs()
            val entry = StoryPacks.catalog(catalog(zip)).stories.single()
            File(repo.root, "${entry.id}-${entry.sha256}.part").writeBytes(byteArrayOf(1, 2, 3))
            server.enqueue(MockResponse().setBody(okio.Buffer().write(zip.readBytes())))
            repo.download(entry) {}
            assertTrue(repo.installed(entry))
        }
    }
}
