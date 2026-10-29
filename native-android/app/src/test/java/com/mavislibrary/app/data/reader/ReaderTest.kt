package com.mavislibrary.app.data.reader

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.mavislibrary.app.data.podcast.PodcastApi
import com.mavislibrary.app.ui.reader.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.*
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class ReaderTest {
    @get:Rule val temp = TemporaryFolder()
    @Before fun setUp() { Dispatchers.setMain(StandardTestDispatcher()) }
    @After fun tearDown() { Dispatchers.resetMain() }

    private fun gateway(code: Int, body: String, seen: (Request) -> Unit = {}) = ReaderApi(PodcastApi("https://gateway.example", execute = { req ->
        seen(req)
        Response.Builder().request(req).protocol(Protocol.HTTP_1_1).code(code).message("fixture").body(body.toResponseBody()).build()
    }, token = { "fixture" }))
    /** The gateway call runs on Dispatchers.IO, so wait for the state rather than for virtual time. */
    private suspend fun ReaderViewModel.loaded() = state.first { it.content != ReaderContent.Loading }.content
    private val target = ReaderTarget("Story", "https://example.com/a", "Site", "site", "a")

    @Test fun textSizeAndThemePersist() = runTest {
        val data = PreferenceDataStoreFactory.create(scope = backgroundScope) { temp.root.resolve("reader.preferences_pb") }
        assertEquals(ReaderSettings(), ReaderPrefs(data).settings.first())
        ReaderPrefs(data).setSize(ReaderTextSize.XL)
        ReaderPrefs(data).setTheme(ReaderTheme.SEPIA)
        assertEquals(ReaderSettings(ReaderTextSize.XL, ReaderTheme.SEPIA), ReaderPrefs(data).settings.first())
    }

    @Test fun readerLoadsArticleThroughGatewayAndMarksRead() = runTest {
        val data = PreferenceDataStoreFactory.create(scope = backgroundScope) { temp.root.resolve("ok.preferences_pb") }
        val api = gateway(200, """{"title":"Full","byline":"Ann","siteName":"Site","image":"http://insecure/x.png","text":"One.\n\nTwo.","words":460,"readMin":2,"url":"https://example.com/a"}""") {
            assertEquals("/api/podsub/article", it.url.encodedPath)
            assertEquals("https://example.com/a", it.url.queryParameter("url"))
            assertEquals("Bearer fixture", it.header("Authorization"))
        }
        val reads = ReadStateStore(data)
        val vm = ReaderViewModel(target, api, ReaderPrefs(data), reads)
        val article = (vm.loaded() as ReaderContent.Ready).article
        assertEquals(listOf("One.", "Two."), article.paragraphs)
        assertNull("non-HTTPS hero images are dropped", article.image)
        assertEquals(setOf("a"), reads.read.first()["site"])
        vm.setSize(ReaderTextSize.L)
        assertEquals(ReaderTextSize.L, vm.state.first { it.settings.size == ReaderTextSize.L }.settings.size)
        assertEquals(ReaderTextSize.L, ReaderPrefs(data).settings.first().size)
    }

    @Test fun readerFallsBackToOriginalOn422AndNetworkFailure() = runTest {
        val data = PreferenceDataStoreFactory.create(scope = backgroundScope) { temp.root.resolve("fail.preferences_pb") }
        val unavailable = ReaderViewModel(target, gateway(422, """{"code":"article_unavailable"}"""), ReaderPrefs(data), ReadStateStore(data))
        val offline = ReaderViewModel(target, object : ReaderService {
            override suspend fun preview(url: String) = error("unused")
            override suspend fun article(url: String): ReaderArticle = throw IOException("Studio unavailable, try later")
        }, ReaderPrefs(data), ReadStateStore(data))
        val insecure = ReaderViewModel(target.copy(url = "http://example.com/a"), gateway(200, "{}") { fail("must not request") }, ReaderPrefs(data), ReadStateStore(data))
        assertEquals(ReaderContent.Original, unavailable.loaded())
        assertEquals(ReaderContent.Original, offline.loaded())
        assertEquals(ReaderContent.Original, insecure.loaded())
    }
}
