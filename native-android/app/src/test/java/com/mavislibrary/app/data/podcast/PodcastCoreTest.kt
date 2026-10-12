package com.mavislibrary.app.data.podcast

import kotlinx.serialization.encodeToString
import kotlinx.coroutines.test.runTest
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test

fun podcastFixtures(): List<PodcastJob> = podcastJson.decodeFromString<PodcastList>(PodcastCoreTest::class.java.getResource("/fixtures/podcasts.json")!!.readText()).items
fun fakeResponse(request: Request, body: String, code: Int = 200, type: String = "application/json", headers: Headers = Headers.Builder().build()) = Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(code).message("fixture").headers(headers).body(body.toResponseBody(type.toMediaType())).build()

fun fakeSignedAudio(request: Request): Response? {
    if (!request.url.encodedPath.startsWith("/api/podcast/audio/")) return null
    val id = request.url.pathSegments.last()
    val expiration = java.time.Instant.now().plusSeconds(600)
    val url = "https://audio.example.org/v1/podcasts/$id/audio?exp=${expiration.epochSecond}&uid=owner&sig=${"0".repeat(64)}"
    return fakeResponse(request, """{"url":"$url","expiresAt":"$expiration"}""")
}

class PodcastCoreTest {
    @Test fun everyStatusAndOptionalFieldsRoundTrip() {
        val jobs = podcastFixtures()
        assertEquals(listOf("queued", "sourcing", "writing", "generating", "ready", "failed"), jobs.map { it.status })
        jobs.forEach { assertEquals(it, podcastJson.decodeFromString<PodcastJob>(podcastJson.encodeToString(it))) }
        assertEquals("NotebookLM is not logged in.", jobs.last().error)
        assertEquals(840, jobs[4].durationSec)
        assertNotNull(jobs[4].transcript)
        assertNull(jobs[4].sources.single().url)
        val error = PodcastError("No readable text at that URL.")
        assertEquals(error, podcastJson.decodeFromString<PodcastError>(podcastJson.encodeToString(error)))
    }
    @Test fun healthAndCreatedRoundTripAndHonestLabels() {
        val h = podcastJson.decodeFromString<StudioHealth>("""{"ok":true,"engines":{"notebooklm":{"available":true,"loggedIn":false,"note":"Login needed"},"local":{"available":true,"note":"CPU voices"}}}""")
        assertEquals("Local voices", h.label)
        assertEquals(h, podcastJson.decodeFromString<StudioHealth>(podcastJson.encodeToString(h)))
        assertEquals("NotebookLM ready", h.copy(engines = h.engines.copy(notebooklm = h.engines.notebooklm.copy(loggedIn = true))).label)
        assertEquals("Studio offline", h.copy(ok = false).label)
        val created = podcastJson.decodeFromString<PodcastCreated>("""{"id":"job1","status":"queued"}""")
        assertEquals(created, podcastJson.decodeFromString<PodcastCreated>(podcastJson.encodeToString(created)))
    }
    @Test fun studioAddressAllowsOnlySpecifiedHttpRanges() {
        listOf("http://100.64.0.0:8140", "http://100.127.255.255:8140", "http://10.0.2.2:8140", "http://10.1.1.2", "http://172.16.0.1", "http://172.31.255.255", "http://192.168.1.71", "https://studio.example.org").forEach { assertNotNull(StudioAddress.parse(it)) }
        listOf("http://100.63.255.255", "http://100.128.0.0", "http://172.15.0.1", "http://172.32.0.1", "http://8.8.8.8", "http://example.org", "http://localhost", "http://127.0.0.1", "http://[::1]", "https://user:pass@example.org", "https://@example.org", "https://example.org/path", "https://example.org?key=x", "https://example.org/#x", "ftp://example.org", "", "http://192.168.1.1.evil.org").forEach { assertTrue("should reject $it", runCatching { StudioAddress.parse(it) }.isFailure) }
    }
    @Test fun audioOriginIsExactAndScopedToAudioPath() {
        val base = "https://studio.example.org:8140"
        assertTrue(StudioAddress.audioAllowed(base, "$base/v1/podcasts/job1/audio"))
        listOf("https://evil.example.org:8140/v1/podcasts/job1/audio", "https://studio.example.org/v1/podcasts/job1/audio", "http://studio.example.org:8140/v1/podcasts/job1/audio", "$base/v1/health", "$base/v1/podcasts/job1/audio?x=y", "https://studio.example.org.evil.org:8140/v1/podcasts/job1/audio").forEach { assertFalse(StudioAddress.audioAllowed(base, it)) }
    }
    @Test fun requestsForAllKindsAndTextCap() {
        val drafts = listOf(PodcastDraft("book", "Book", gutenbergId = 1342), PodcastDraft.extracted("book", "Imported", "x".repeat(400_010)), PodcastDraft.extracted("chapter", "Chapter 2", "Chapter text"), PodcastDraft("article", "Article", url = "https://example.org/article"), PodcastDraft("topic", "Robotics", "Humanoid robotics"))
        drafts.forEach { draft -> val r = draft.request("debate", "long", "local"); assertEquals(r, podcastJson.decodeFromString<PodcastRequest>(podcastJson.encodeToString(r))) }
        assertEquals(400_000, drafts[1].text.length)
        assertEquals(400_010, drafts[1].originalChars)
        assertEquals(1342, drafts[0].request("brief", "short", "auto").gutenbergId)
        assertTrue(podcastJson.encodeToString(drafts[0].request("brief", "short", "auto")).contains("\"gutenbergId\":1342"))
        assertTrue(runCatching { PodcastRequest("chapter", "Too long", text = "x".repeat(400_001)).validated() }.isFailure)
        assertTrue(runCatching { PodcastRequest("article", "Bad", url = "https://user:password@example.org").validated() }.isFailure)
        assertTrue(runCatching { PodcastRequest("book", "Empty").validated() }.isFailure)
    }
    @Test fun shareRoutingAndKidsGate() {
        assertTrue(routePodcastShare("John 3:16") is PodcastShare.Bible)
        assertTrue(routePodcastShare("https://example.org/article") is PodcastShare.Lounge)
        assertFalse((routePodcastShare("https://example.org/article") as PodcastShare.Lounge).draft.confirmShare)
        assertEquals(PodcastShare.Unsupported, routePodcastShare("http://example.org"))
        assertEquals(PodcastShare.Unsupported, routePodcastShare("https://user:pass@example.org"))
        assertEquals("chapter", (routePodcastShare("Shared article text") as PodcastShare.Lounge).draft.kind)
        assertFalse(loungeRouteAllowed(null)); assertFalse(loungeRouteAllowed(true)); assertTrue(loungeRouteAllowed(false))
    }
    @Test fun gatewayExactRoutesAuthAndStringGutenbergIdWithFakeHttp() = runTest {
        val seen = mutableListOf<Request>()
        val api = PodcastApi("https://site.example.org", execute = { request ->
            seen += request
            when {
                request.method == "POST" -> fakeResponse(request, """{"id":"job1","status":"queued"}""", 201)
                request.method == "DELETE" -> fakeResponse(request, """{"id":"job1","status":"deleted"}""")
                request.url.encodedPath == "/api/podcast/list" -> fakeResponse(request, """{"jobs":${podcastJson.encodeToString(podcastFixtures())},"cursor":null}""")
                else -> fakeResponse(request, podcastJson.encodeToString(podcastFixtures()[4]))
            }
        }, token = { "fixture-session" })
        api.create(PodcastRequest("book", "Book", gutenbergId = 1342)); api.get("job1"); api.list(); api.delete("job1")
        seen.forEach { assertEquals("Bearer fixture-session", it.header("Authorization")); assertNull(it.header("x-mavis-access")); assertNull(it.header("Cookie")) }
        assertEquals(listOf("/api/podcast/create", "/api/podcast/job/job1", "/api/podcast/list", "/api/podcast/job/job1"), seen.map { it.url.encodedPath })
        val body = Buffer().apply { seen[0].body!!.writeTo(this) }.readUtf8()
        assertTrue(body.contains("\"gutenbergId\":\"1342\""))
        assertTrue(body.contains("\"style\":\"deep-dive\""))
        val bad = PodcastApi("https://site.example.org", execute = { fakeResponse(it, """{"message":"Please supply a readable chapter."}""", 400) }, token = { "fixture" })
        assertEquals("Please supply a readable chapter.", runCatching { bad.create(PodcastRequest("chapter", "Chapter", text = "Text")) }.exceptionOrNull()?.message)
    }
    @Test fun audioUsesSignedUrlAndRangeButNeverForwardsCredentials() = runTest {
        val seen = mutableListOf<Request>()
        val api = PodcastApi("https://site.example.org", execute = { request ->
            seen += request
            fakeSignedAudio(request) ?: fakeResponse(request, "", 302, headers = Headers.headersOf("Location", "https://other.example.org/audio"))
        }, owner = { "fixture-owner" }, token = { "fixture-session" })
        val request = api.audioRequest(api.audioUrl("job1"), "bytes=1024-")
        assertNull(request.header("Authorization")); assertNull(request.header("x-mavis-access")); assertEquals("bytes=1024-", request.header("Range"))
        assertEquals("audio.example.org", request.url.host)
        assertEquals("Bearer fixture-session", seen.single().header("Authorization"))
        assertEquals("fixture-owner", seen.single().header("x-mavis-access"))
        assertTrue(runCatching { api.audioRequest("https://other.example.org/v1/podcasts/job1/audio") }.isFailure)
        assertTrue(runCatching { api.audio(api.audioUrl("job1")) }.isFailure)
        assertEquals(3, seen.size)
        assertNull(seen.last().header("Authorization")); assertNull(seen.last().header("x-mavis-access"))
    }
    @Test fun expiredMalformedOrUnsignedAudioLinksAreRejectedWithoutPrintingTheLink() = runTest {
        for (body in listOf("not JSON", """{"url":"https://audio.example.org/v1/podcasts/job1/audio","expiresAt":"2000-01-01T00:00:00Z"}""", """{"url":"http://audio.example.org/v1/podcasts/job1/audio?exp=9999999999&uid=owner&sig=private","expiresAt":"2099-01-01T00:00:00Z"}""")) {
            val api = PodcastApi("https://site.example.org", execute = { fakeResponse(it, body) }, token = { "fixture" })
            val error = runCatching { api.audioRequest(api.audioUrl("job1")) }.exceptionOrNull()
            assertNotNull(error)
            assertFalse(error!!.message.orEmpty().contains("private"))
            assertFalse(error.message.orEmpty().contains("http"))
        }
    }
    @Test fun paginationAndKidsModeFailClosed() = runTest {
        val seen = mutableListOf<Request>()
        val api = PodcastApi("https://site.example.org", execute = {
            seen += it
            fakeResponse(it, if (seen.size == 1) """{"jobs":[],"cursor":"pod/user/next"}""" else """{"jobs":[],"cursor":null}""")
        }, token = { "fixture" })
        assertTrue(api.list().isEmpty())
        assertEquals("pod/user/next", seen.last().url.queryParameter("cursor"))
        val blocked = PodcastApi("https://site.example.org", execute = { error("HTTP must not run in Kids mode") }, adult = { false }, token = { "fixture" })
        assertTrue(runCatching { blocked.list() }.isFailure)
        assertTrue(runCatching { blocked.create(PodcastRequest("topic", "Topic", query = "Space")) }.isFailure)
        assertFalse("brief" in podcastStyles)
    }
}
