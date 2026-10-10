package com.mavislibrary.app.data.repo

import com.mavislibrary.app.Fixtures
import com.mavislibrary.app.data.api.ApiException
import com.mavislibrary.app.data.api.Endpoints
import com.mavislibrary.app.data.api.RemoteSource
import com.mavislibrary.app.data.model.BookSource
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private class MemoryCache : ResponseCache {
    val map = mutableMapOf<String, CachedResponse>()
    override suspend fun get(key: String) = map[key]
    override suspend fun put(key: String, body: String, fetchedAt: Long) { map[key] = CachedResponse(body, fetchedAt) }
}

private class FakeRemote(var respond: () -> String) : RemoteSource {
    var calls = 0
    override suspend fun get(path: String, params: List<Pair<String, String>>): String { calls++; return respond() }
}

class CachedFetcherTest {
    private val cache = MemoryCache()
    private var online = true
    private var now = 1_000_000L
    private val remote = FakeRemote { "network" }
    private val fetcher = CachedFetcher(remote, cache, { online }, { now }, freshForMs = 60_000)
    private val params = listOf("q" to "Austen")

    @Test
    fun networkResultIsCachedThenServedFresh() = runTest {
        val first = fetcher.fetch("/p", params) { it }
        assertEquals(Origin.NETWORK, first.origin)
        now += 30_000
        val second = fetcher.fetch("/p", params) { it }
        assertEquals(Origin.CACHE_FRESH, second.origin)
        assertEquals(1, remote.calls)
    }

    @Test
    fun offlineServesStaleCache() = runTest {
        fetcher.fetch("/p", params) { it }
        now += 10 * 60_000
        online = false
        val res = fetcher.fetch("/p", params) { it }
        assertEquals(Origin.CACHE_STALE, res.origin)
        assertTrue(res.isStale)
        assertEquals(1_000_000L, res.fetchedAt)
        assertEquals(1, remote.calls)
    }

    @Test
    fun offlineWithoutCacheThrowsOffline() = runTest {
        online = false
        val e = runCatching { fetcher.fetch("/p", params) { it } }.exceptionOrNull() as ApiException
        assertTrue(e.offline)
        assertEquals(0, remote.calls)
    }

    @Test
    fun serverErrorFallsBackToCacheButClientErrorDoesNot() = runTest {
        fetcher.fetch("/p", params) { it }
        now += 10 * 60_000
        remote.respond = { throw ApiException("upstream_error", "x", status = 502) }
        assertEquals(Origin.CACHE_STALE, fetcher.fetch("/p", params) { it }.origin)
        remote.respond = { throw ApiException("bad_request", "bad", status = 400) }
        val e = runCatching { fetcher.fetch("/p", params) { it } }.exceptionOrNull() as ApiException
        assertEquals(400, e.status)
    }

    @Test
    fun unparseableResponseIsNotCached() = runTest {
        remote.respond = { "garbage" }
        runCatching { fetcher.fetch("/p", params) { throw ApiException("bad_response", "x") } }
        assertTrue(cache.map.isEmpty())
    }

    @Test
    fun cacheKeyIgnoresParamOrderAndCase() {
        assertEquals(
            CachedFetcher.cacheKey("/p", listOf("q" to "Austen ", "page" to "1")),
            CachedFetcher.cacheKey("/p", listOf("page" to "1", "q" to "austen")),
        )
    }

    @Test
    fun searchRepositoryFiltersConfirmedCopyright() = runTest {
        remote.respond = { Fixtures.read("catalog_search.json") }
        val res = SearchRepository(fetcher).search(BookSource.GUTENBERG, "austen")
        assertEquals(listOf("gutenberg:1342", "gutenberg:105"), res.value.books.map { it.key })
        assertTrue(res.value.hasNext)
        assertTrue(cache.map.keys.single().startsWith(Endpoints.CATALOG))
    }
}
