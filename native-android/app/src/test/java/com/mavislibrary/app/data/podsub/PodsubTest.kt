package com.mavislibrary.app.data.podsub

import com.mavislibrary.app.data.api.ApiException
import com.mavislibrary.app.data.briefing.BriefingSettings
import com.mavislibrary.app.data.feeds.*
import com.mavislibrary.app.data.podcast.*
import com.mavislibrary.app.ui.lounge.PodsubViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test
import java.time.*

private val contract = """{"version":1,"sites":[{"id":"ai","name":"AI","feedUrl":"https://example.com/rss","siteUrl":"https://example.com/","mode":"top","count":1,"delivery":"briefing","priority":0}],"schedule":{"enabled":true,"readyBy":"07:30","days":["mon","fri"],"timezone":"America/New_York"},"engine":"notebooklm","rank":"newest","keepDays":7,"notify":{"telegram":false},"nextRunAt":"2026-10-28T10:30:00Z","updatedAt":"2026-10-27T12:00:00Z","today":{"used":2,"limit":30,"date":"2026-10-27"},"lastRun":{"startedAt":"2026-10-27T10:30:00Z","finishedAt":"2026-10-27T10:30:15Z","status":"partial","episodes":[{"jobId":"j1","title":"Briefing","siteIds":["ai"]}],"skipped":[{"siteId":"other","reason":"skipped_limit"}]}}"""
private fun site(id: String, mode: String = SitePodcast.BRIEFING) = FeedSource(id, id, "https://example.com/$id/rss", "https://example.com/$id", 0, podcast = mode)
private class MemoryMigration : MigrationState {
    val values = mutableMapOf<String, List<String>>()
    override suspend fun followed(account: String) = values[account]
    override suspend fun complete(account: String, ids: List<String>) { values[account] = ids }
}
private class FakeService : PodsubService {
    var response = podcastJson.decodeFromString<SubscriptionResponse>(contract)
    var puts = 0
    var fail = false
    override suspend fun get() = response
    override suspend fun put(document: SubscriptionDocument): SubscriptionResponse {
        puts++
        if (fail) throw ApiException("unavailable", "Offline", 503)
        response = podcastJson.decodeFromString<SubscriptionResponse>(podcastJson.encodeToString(document)).copy(updatedAt = "saved", today = response.today)
        return response
    }
    override suspend fun today() = emptyList<PodcastJob>()
    override suspend fun runs() = listOfNotNull(response.lastRun)
    override suspend fun runNow() = SubscriptionRun("now", status = "skipped_empty")
}
class PodsubTest {
    @Test fun contractRoundTripAndStrictPutFields() {
        val response = podcastJson.decodeFromString<SubscriptionResponse>(contract)
        assertEquals(podcastJson.parseToJsonElement(contract), podcastJson.encodeToJsonElement(response))
        val put = podcastJson.encodeToJsonElement(response.document()).jsonObject
        assertEquals(setOf("version", "sites", "schedule", "engine", "rank", "keepDays", "notify"), put.keys)
        assertEquals(response.document(), podcastJson.decodeFromString<SubscriptionDocument>(put.toString()))
        assertEquals("skipped_limit", response.lastRun!!.skipped.single().reason)
    }
    @Test fun defaultsAndValidation() {
        val doc = SubscriptionDocument()
        assertEquals("notebooklm", doc.engine); assertEquals("newest", doc.rank); assertEquals(7, doc.keepDays)
        assertFalse(doc.schedule.enabled)
        listOf(0, 31).forEach { assertTrue(runCatching { doc.copy(keepDays = it).validated() }.isFailure) }
        assertTrue(runCatching { doc.copy(engine = "local").validated() }.isFailure)
        assertTrue(runCatching { doc.copy(schedule = SubscriptionSchedule(true, "25:00")).validated() }.isFailure)
        assertTrue(runCatching { doc.copy(schedule = SubscriptionSchedule(true, days = emptyList())).validated() }.isFailure)
        val off = doc.copy(sites = listOf(SiteRule("a", "a", "https://a.com", "https://a.com", mode = "off"))).validated()
        assertFalse(podcastJson.encodeToJsonElement(off).jsonObject["sites"]!!.jsonArray[0].jsonObject.containsKey("count"))
    }
    @Test fun migrationMapsModesTimeAndTimezone() {
        val doc = migrateSubscription(listOf(site("a"), site("b", SitePodcast.OWN), site("c", SitePodcast.OFF), site("d").copy(enabled = false)),
            BriefingSettings(true, LocalTime.of(8, 45)), ZoneId.of("America/New_York"))
        assertEquals(listOf("top", "top", "off", "off"), doc.sites.map { it.mode })
        assertEquals(listOf("briefing", "separate", "briefing", "briefing"), doc.sites.map { it.delivery })
        assertEquals(listOf(0, 1, 2, 3), doc.sites.map { it.priority })
        assertEquals(listOf(1, 1, null, null), doc.sites.map { it.count })
        assertEquals("08:45", doc.schedule.readyBy); assertTrue(doc.schedule.enabled)
        assertEquals("America/New_York", doc.schedule.timezone)
    }
    @Test fun failedMigrationRetriesAndSuccessfulMigrationIsPerAccount() = runTest {
        val api = FakeService(); val state = MemoryMigration(); var uid = "alice"
        val repo = PodsubRepository(api, state, { uid }, { listOf(site("phone")) }, { BriefingSettings() })
        api.fail = true
        assertTrue(runCatching { repo.load() }.isFailure); assertNull(state.followed(uid))
        api.fail = false; repo.load(); assertEquals(listOf("phone"), state.followed(uid))
        val puts = api.puts; repo.load(); assertEquals(puts, api.puts)
        uid = "bob"; repo.load(); assertEquals(puts + 1, api.puts)
    }
    @Test fun syncPreservesServerRulesAndRemovesOnlyKnownPhoneSites() = runTest {
        val api = FakeService(); val state = MemoryMigration(); var sites = listOf(site("phone"))
        val repo = PodsubRepository(api, state, { "alice" }, { sites }, { BriefingSettings() })
        val first = repo.load(); assertTrue(first.schedule.enabled); assertEquals("07:30", first.schedule.readyBy)
        sites = emptyList(); val next = repo.load()
        assertEquals(listOf("ai"), next.sites.map { it.id })
    }
    @Test fun cleanupBoundaryInvalidDatesAndRange() {
        val now = Instant.parse("2026-10-27T12:00:00Z")
        assertTrue(downloadExpired("2026-10-20T11:59:59Z", 7, now))
        assertFalse(downloadExpired("2026-10-20T12:00:00Z", 7, now))
        assertFalse(downloadExpired("invalid", 7, now))
        assertFalse(downloadExpired("2026-10-28T12:00:00Z", 1, now))
        assertTrue(runCatching { downloadExpired("invalid", 0, now) }.isFailure)
    }
    @Test fun newServerDocumentMigratesOnceWithScheduleDisabled() = runTest {
        val api = FakeService().apply { response = SubscriptionResponse() }
        val marker = MemoryMigration()
        val repo = PodsubRepository(api, marker, { "new" }, { listOf(site("a"), site("b")) }, { BriefingSettings() })
        val loaded = repo.load()
        assertFalse(loaded.schedule.enabled); assertEquals("notebooklm", loaded.engine)
        assertTrue(loaded.sites.all { it.mode == "top" && it.count == 1 && it.delivery == "briefing" })
        assertEquals(1, api.puts); repo.load(); assertEquals(1, api.puts)
    }
    @Test fun todayAcceptsEpisodeEnvelopeAndKeepsStatus() = runTest {
        val job = podcastFixtures()[4]
        val gateway = PodcastApi("https://gateway.example.com", execute = {
            fakeResponse(it, "{\"episodes\":[${podcastJson.encodeToString(job)}]}")
        }, token = { "fixture" })
        assertEquals(listOf(job), PodsubApi(gateway).today())
    }
    @Test fun missingAuthAndKidsNeverSendSubscriptionRequests() = runTest {
        var calls = 0
        val anonymous = PodcastApi("https://gateway.example.com", execute = { calls++; fakeResponse(it, contract) }, token = { null })
        assertTrue(runCatching { PodsubApi(anonymous).get() }.isFailure)
        val kids = PodcastApi("https://gateway.example.com", execute = { calls++; fakeResponse(it, contract) }, adult = { false }, token = { "fixture" })
        assertTrue(runCatching { PodsubApi(kids).runNow() }.isFailure)
        assertEquals(0, calls)
    }
    @Test fun gatewayUsesAccountAuthAndOnlyContractRoutes() = runTest {
        val calls = mutableListOf<String>()
        val gateway = PodcastApi("https://gateway.example.com", execute = { request ->
            assertEquals("Bearer fixture-token", request.header("Authorization"))
            calls += "${request.method} ${request.url.encodedPath}"
            val body = when (request.url.encodedPath) {
                "/api/podsub/today" -> "[]"
                "/api/podsub/runs" -> "{\"runs\":[]}"
                "/api/podsub/run-now" -> """{"startedAt":"now","status":"skipped_empty","episodes":[],"skipped":[]}"""
                else -> { if (request.method == "PUT") assertFalse(Buffer().also { request.body!!.writeTo(it) }.readUtf8().contains("nextRunAt")); contract }
            }
            fakeResponse(request, body)
        }, token = { "fixture-token" })
        val api = PodsubApi(gateway)
        api.get(); api.put(SubscriptionDocument()); api.today(); api.runs(); api.runNow()
        assertEquals(listOf("GET /api/podsub", "PUT /api/podsub", "GET /api/podsub/today", "GET /api/podsub/runs", "POST /api/podsub/run-now"), calls)
    }
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun viewModelUsesServerLimitAndRetainsDirtyEditsOnRefresh() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val api = FakeService()
            val vm = PodsubViewModel(PodsubRepository(api, MemoryMigration(), { "owner" }, { emptyList() }, { BriefingSettings() }))
            assertEquals("Today: usage unavailable", vm.state.value.meter)
            vm.refresh(); advanceUntilIdle(); assertEquals("Today: 2 of 30 used", vm.state.value.meter)
            vm.edit { it.copy(keepDays = 10) }
            api.response = api.response.copy(today = DailyUsage(3, 4, "2026-10-27"))
            vm.refresh(); advanceUntilIdle()
            assertEquals("Today: 3 of 4 used", vm.state.value.meter); assertEquals(10, vm.state.value.document!!.keepDays)
            vm.save(); advanceUntilIdle(); assertFalse(vm.state.value.dirty)
        } finally { Dispatchers.resetMain() }
    }
}
