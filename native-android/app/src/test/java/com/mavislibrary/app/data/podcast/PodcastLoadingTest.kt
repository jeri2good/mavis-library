package com.mavislibrary.app.data.podcast

import com.mavislibrary.app.data.api.ApiException
import com.mavislibrary.app.data.api.loungeError
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import okhttp3.Request
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.time.Instant

/** A studio whose answers each test sets directly. Nothing here touches a network. */
class ScriptedStudio : PodcastService {
    val jobs = LinkedHashMap<String, PodcastJob>()
    var listed: (() -> PodcastListing)? = null
    var onCreate: (PodcastRequest) -> PodcastCreated = { PodcastCreated("new${++made}", "queued") }
    var onGet: ((String) -> PodcastJob)? = null
    var onDelete: (String) -> Unit = {}
    var made = 0
    val creates = mutableListOf<PodcastRequest>()
    val gets = mutableListOf<String>()
    val deletes = mutableListOf<String>()
    var lists = 0
    override suspend fun health() = StudioHealth(true, StudioEngines(StudioEngine(false), StudioEngine(true)))
    override suspend fun create(request: PodcastRequest): PodcastCreated { creates += request; return onCreate(request) }
    override suspend fun get(id: String): PodcastJob { gets += id; return onGet?.invoke(id) ?: jobs[id] ?: throw notFound() }
    override suspend fun list() = listing().jobs
    override suspend fun listing(): PodcastListing { lists++; return listed?.invoke() ?: PodcastListing(jobs.values.toList()) }
    override suspend fun delete(id: String) { deletes += id; onDelete(id); jobs.remove(id) }
    companion object { fun notFound() = ApiException("not_found", "That podcast was not found.", 404) }
}
/** Counts whole-library writes, and can be told to fail saving. */
class CountingLibrary : PodcastLibrary {
    override val library = MutableStateFlow<List<SavedPodcast>>(emptyList())
    var writes = 0
    var failSaves = 0
    override suspend fun save(podcast: SavedPodcast) {
        if (failSaves-- > 0) throw IOException("disk full")
        writes++
        library.value = listOf(podcast.mergedOver(library.value.find { it.key == podcast.key })) + library.value.filterNot { it.key == podcast.key }
    }
    override suspend fun remove(id: String, studio: String) { writes++; library.value = library.value.filterNot { it.job.id == id && it.studio == studio } }
    override suspend fun update(change: (List<SavedPodcast>) -> List<SavedPodcast>) {
        val after = change(library.value)
        if (after != library.value) { writes++; library.value = after }
    }
}
fun job(id: String, status: String = "ready", title: String = "Episode $id", createdAt: String = "2026-11-10T08:00:00Z", transcript: String? = null) =
    PodcastJob(id, status, title = title, kind = "topic", style = "deep-dive", createdAt = createdAt, transcript = transcript)

@OptIn(ExperimentalCoroutinesApi::class)
class PodcastLoadingTest {
    private fun api(answer: (Request, Int) -> okhttp3.Response): Pair<PodcastApi, MutableList<Request>> {
        val seen = mutableListOf<Request>()
        return PodcastApi("https://site.example.org", execute = { seen += it; answer(it, seen.size) }, token = { "fixture" }) to seen
    }

    // --- R1: one odd job no longer makes the list unreadable
    @Test fun listKeepsReadableJobsWhenOneIsMissingFieldsOrBroken() = runTest {
        val (api, _) = api { request, _ -> fakeResponse(request, """{"jobs":[
            {"id":"bare","status":"ready"},
            {"id":"full","status":"writing","progress":40,"title":"Full","kind":"topic","style":"debate"},
            {"status":"ready","title":"no id"},
            {"id":"../evil","status":"ready","title":"bad id"},
            "not an object"
        ],"cursor":null}""") }
        val listing = api.listing()
        assertEquals(listOf("bare", "full"), listing.jobs.map { it.id })
        assertEquals("Untitled podcast", listing.jobs[0].title)
        assertEquals("", listing.jobs[0].kind)
        assertEquals(3, listing.skipped); assertTrue(listing.complete)
        assertEquals(listOf("bare", "full"), api.list().map { it.id })
    }
    @Test fun aSingleJobThatCannotBeReadIsATypedErrorNotACrash() = runTest {
        val (api, _) = api { request, _ -> fakeResponse(request, """{"unexpected":true}""") }
        val error = runCatching { api.get("job1") }.exceptionOrNull()
        assertEquals("bad_response", (error as ApiException).code)
        assertEquals(PodcastProblemKind.BAD_RESPONSE, error.toPodcastProblem().kind)
    }

    // --- R7: the cursor loop keeps what it loaded
    @Test fun repeatedCursorOrFailedLaterPageKeepsLoadedPagesAndSaysIncomplete() = runTest {
        val page = """{"jobs":[${podcastJson.encodeToString(job("a"))}],"cursor":"pod/user/same"}"""
        val (looping, seenLoop) = api { request, _ -> fakeResponse(request, page) }
        val loop = looping.listing()
        assertEquals(listOf("a"), loop.jobs.map { it.id }); assertFalse(loop.complete)
        assertEquals(2, seenLoop.size)
        val (failing, _) = api { request, n -> if (n == 1) fakeResponse(request, page) else fakeResponse(request, """{"code":"studio_unavailable"}""", 502) }
        val partial = failing.listing()
        assertEquals(listOf("a"), partial.jobs.map { it.id }); assertFalse(partial.complete)
        val (down, _) = api { request, _ -> fakeResponse(request, """{"code":"studio_unavailable"}""", 502) }
        assertEquals(502, (runCatching { down.listing() }.exceptionOrNull() as ApiException).status)
        val (signedOut, _) = api { request, n -> if (n == 1) fakeResponse(request, page) else fakeResponse(request, "{}", 401) }
        assertEquals(401, (runCatching { signedOut.listing() }.exceptionOrNull() as ApiException).status)
        val (garbage, _) = api { request, _ -> fakeResponse(request, "<html>") }
        assertEquals(PodcastProblemKind.BAD_RESPONSE, runCatching { garbage.listing() }.exceptionOrNull()!!.toPodcastProblem().kind)
    }
    @Test fun endlessPagesStopAtTheCap() = runTest {
        val (api, seen) = api { request, n -> fakeResponse(request, """{"jobs":[],"cursor":"pod/user/$n"}""") }
        assertFalse(api.listing().complete)
        assertEquals(50, seen.size)
    }

    // --- R2 / U3: errors are typed, and the gateway's rate limit is not called a daily limit
    @Test fun gatewayErrorsBecomeTypedProblems() {
        fun kind(status: Int, body: String) = loungeError(status, body).toPodcastProblem()
        assertEquals(PodcastProblemKind.SLOW_DOWN, kind(429, """{"error":"rate_limited","message":"Too many podcast requests. Wait a minute."}""").kind)
        assertFalse(loungeError(429, """{"error":"rate_limited"}""").message.contains("Daily"))
        assertEquals(PodcastProblemKind.DAILY_LIMIT, kind(429, """{"code":"daily_limit","error":"daily_limit"}""").kind)
        assertEquals(PodcastProblemKind.ACTIVE_LIMIT, kind(429, """{"code":"active_limit"}""").kind)
        val busy = kind(429, """{"code":"studio_busy","retryAfter":90}""")
        assertEquals(PodcastProblemKind.STUDIO_BUSY, busy.kind); assertEquals(90, busy.retryAfterSec)
        assertNull(kind(429, """{"code":"studio_busy","retryAfter":999999}""").retryAfterSec)
        assertEquals(PodcastProblemKind.SIGN_IN, kind(401, "{}").kind); assertTrue(kind(401, "{}").needsSettings)
        assertEquals("Request access", kind(403, "{}").settingsLabel)
        assertEquals(PodcastProblemKind.NOT_FOUND, kind(404, """{"code":"not_found"}""").kind)
        assertEquals(PodcastProblemKind.STUDIO_DOWN, kind(502, """{"code":"studio_unavailable"}""").kind)
        assertEquals(PodcastProblemKind.BAD_RESPONSE, kind(502, """{"code":"studio_bad_response"}""").kind)
        assertEquals(PodcastProblemKind.NOT_CONFIGURED, kind(503, """{"code":"studio_not_configured"}""").kind)
        assertEquals(PodcastProblemKind.INVALID, kind(400, """{"message":"Please supply a readable chapter."}""").kind)
        assertEquals("Please supply a readable chapter.", kind(400, """{"message":"Please supply a readable chapter."}""").message)
        assertEquals(PodcastProblemKind.OFFLINE, IOException("socket closed at 10.0.0.1").toPodcastProblem().kind)
        assertFalse(IOException("socket closed at 10.0.0.1").toPodcastProblem().message.contains("10.0.0.1"))
        assertEquals(PodcastProblemKind.INVALID, IllegalArgumentException("Add the source for this podcast.").toPodcastProblem().kind)
        assertTrue(kind(502, "{}").temporary); assertFalse(kind(401, "{}").temporary)
    }
    @Test fun aDroppedConnectionIsReportedAsOfflineNotAsTheStudio() = runTest {
        val api = PodcastApi("https://site.example.org", execute = { throw IOException("connection reset") }, token = { "fixture" })
        val error = runCatching { api.list() }.exceptionOrNull() as ApiException
        assertTrue(error.offline); assertNull(error.status)
        assertEquals(PodcastProblemKind.OFFLINE, error.toPodcastProblem().kind)
    }

    // --- A1 / A2: signed links are reused, re-signed once when refused, and failures are typed
    private fun signed(request: Request, expires: Instant) = fakeResponse(request,
        """{"url":"https://audio.example.org/v1/podcasts/job1/audio?exp=${expires.epochSecond}&uid=owner&sig=${"0".repeat(64)}","expiresAt":"$expires"}""")
    @Test fun oneSignedLinkServesEverySeekUntilShortlyBeforeItRunsOut() = runTest {
        val now = Instant.parse("2026-11-12T12:00:00Z")
        var signs = 0
        val api = PodcastApi("https://site.example.org", execute = { signs++; signed(it, now.plusSeconds(600)) }, token = { "fixture" })
        val url = api.audioUrl("job1")
        val first = api.audioRequest(url, "bytes=0-", now)
        assertEquals(first.url, api.audioRequest(url, "bytes=5000-", now.plusSeconds(300)).url)
        assertEquals(1, signs)
        api.audioRequest(url, null, now.plusSeconds(545)); assertEquals(2, signs)
        api.forgetAudioLink("job1"); api.audioRequest(url, null, now); assertEquals(3, signs)
    }
    @Test fun aSignedLinkIsNotReusedForAnotherAccount() = runTest {
        var session = "first"; var signs = 0
        val api = PodcastApi("https://site.example.org", execute = { signs++; signed(it, Instant.now().plusSeconds(600)) }, token = { session })
        api.audioRequest(api.audioUrl("job1")); session = "second"; api.audioRequest(api.audioUrl("job1"))
        assertEquals(2, signs)
    }
    @Test fun aRefusedLinkIsSignedOnceMoreThenReportedAsExpired() = runTest {
        val seen = mutableListOf<String>()
        var refusals = 1
        val api = PodcastApi("https://site.example.org", execute = { request ->
            seen += request.url.host
            if (request.url.host == "site.example.org") signed(request, Instant.now().plusSeconds(600))
            else if (refusals-- > 0) fakeResponse(request, "", 403) else fakeResponse(request, "mp3", type = "audio/mpeg")
        }, token = { "fixture" })
        api.audio(api.audioUrl("job1")).close()
        assertEquals(listOf("site.example.org", "audio.example.org", "site.example.org", "audio.example.org"), seen)
        refusals = 5; seen.clear()
        val error = runCatching { api.audio(api.audioUrl("job1")) }.exceptionOrNull() as PodcastAudioException
        assertEquals(AudioProblem.EXPIRED, error.problem)
        assertEquals(2, seen.count { it == "audio.example.org" })
        assertFalse(error.message!!.contains("http"))
    }
    @Test fun audioFailuresSayWhatWentWrong() = runTest {
        fun gateway(status: Int, code: String) = PodcastApi("https://site.example.org", execute = { fakeResponse(it, """{"code":"$code"}""", status) }, token = { "fixture" })
        suspend fun problem(api: PodcastApi) = (runCatching { api.audio(api.audioUrl("job1")) }.exceptionOrNull() as PodcastAudioException).problem
        assertEquals(AudioProblem.NOT_READY, problem(gateway(409, "audio_not_ready")))
        assertEquals(AudioProblem.GONE, problem(gateway(404, "not_found")))
        assertEquals(AudioProblem.SIGN_IN, problem(gateway(401, "sign_in_required")))
        assertEquals(AudioProblem.UNAVAILABLE, problem(gateway(502, "studio_unavailable")))
        assertEquals(AudioProblem.OFFLINE, problem(PodcastApi("https://site.example.org", execute = { throw IOException("no route") }, token = { "fixture" })))
        fun studio(status: Int) = PodcastApi("https://site.example.org", execute = { fakeSignedAudio(it) ?: fakeResponse(it, "", status) }, token = { "fixture" })
        assertEquals(AudioProblem.RANGE, problem(studio(416)))
        assertEquals(AudioProblem.GONE, problem(studio(404)))
        assertEquals(AudioProblem.UNAVAILABLE, problem(studio(500)))
        AudioProblem.entries.forEach { assertSame(it, AudioProblem.of(it.message)); assertFalse(it.message.contains("://")) }
        assertNull(AudioProblem.of("something else"))
    }

    // --- R4 / R3: what a newer answer keeps from the older one
    @Test fun mergeKeepsTranscriptDownloadAndRetrySourceAndNeverUnfinishesAPodcast() {
        val request = PodcastRequest("topic", "Topic", query = "Space")
        val old = SavedPodcast(job("a", transcript = "Host A: Hello."), "studio", "private.mp3", request, "user-1")
        val fromList = SavedPodcast(job("a", createdAt = "").copy(title = "", durationSec = 600), "studio")
        val merged = fromList.mergedOver(old)
        assertEquals("Host A: Hello.", merged.job.transcript); assertEquals("Episode a", merged.job.title)
        assertEquals("2026-11-10T08:00:00Z", merged.job.createdAt); assertEquals(600, merged.job.durationSec)
        assertEquals("private.mp3", merged.localFile); assertEquals(request, merged.request); assertEquals("user-1", merged.accountId)
        assertEquals("ready", SavedPodcast(job("a", "generating"), "studio").mergedOver(old).job.status)
        assertEquals("failed", SavedPodcast(job("a", "failed"), "studio").mergedOver(SavedPodcast(job("a", "writing"), "studio")).job.status)
        val other = SavedPodcast(job("b", "queued"), "studio")
        assertEquals(other, other.mergedOver(old))
        assertEquals(other, other.mergedOver(null))
    }
}
