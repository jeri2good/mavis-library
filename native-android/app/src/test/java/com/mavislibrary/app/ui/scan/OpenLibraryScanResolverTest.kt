package com.mavislibrary.app.ui.scan

import com.mavislibrary.app.data.api.ApiException
import com.mavislibrary.app.data.api.Endpoints
import com.mavislibrary.app.data.api.RemoteSource
import com.mavislibrary.app.data.repo.CachedFetcher
import com.mavislibrary.app.data.repo.CachedResponse
import com.mavislibrary.app.data.repo.ResponseCache
import com.mavislibrary.app.data.repo.SearchRepository
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class OpenLibraryScanResolverTest {
    private val cache = object : ResponseCache {
        override suspend fun get(key: String): CachedResponse? = null
        override suspend fun put(key: String, body: String, fetchedAt: Long) = Unit
    }
    private fun resolver(online: Boolean = true, source: RemoteSource) = OpenLibraryScanResolver(SearchRepository(CachedFetcher(source, cache, { online })))

    @Test fun lookupSendsOnlyIsbnToExistingEndpoint() = runTest {
        var requested = emptyList<Pair<String, String>>()
        val resolver = resolver(source = RemoteSource { path, params ->
            assertEquals(Endpoints.OPEN_LIBRARY, path)
            requested = params
            """{"results":[{"id":"OL1W","title":"A book","authors":["Writer"],"isbn":"9780306406157"}]}"""
        })
        val found = resolver.resolve("9780306406157")
        assertEquals("A book", (found as ScanLookup.Found).book.title)
        assertEquals(listOf("q" to "isbn:9780306406157", "page" to "1"), requested)
    }

    @Test fun missingOfflineAndServerErrorHaveDistinctResults() = runTest {
        assertEquals(ScanLookup.NotFound, resolver(source = RemoteSource { _, _ -> """{"results":[]}""" }).resolve("9780306406157"))
        assertEquals(ScanLookup.Offline, resolver(online = false, source = RemoteSource { _, _ -> throw AssertionError("offline must not call remote") }).resolve("9780306406157"))
        assertEquals(ScanLookup.Error, resolver(source = RemoteSource { _, _ -> throw ApiException("bad", "Server error", status = 502) }).resolve("9780306406157"))
    }
}
