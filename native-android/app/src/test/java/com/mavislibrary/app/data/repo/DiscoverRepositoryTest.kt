package com.mavislibrary.app.data.repo

import com.mavislibrary.app.data.api.ApiParser
import com.mavislibrary.app.data.api.Endpoints
import com.mavislibrary.app.data.api.RemoteSource
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private class MapRemote(val bodies: Map<String, String>) : RemoteSource {
    val paths = mutableListOf<String>()
    override suspend fun get(path: String, params: List<Pair<String, String>>): String {
        paths += path + "?" + params.joinToString("&") { "${it.first}=${it.second}" }
        return bodies.getValue(path)
    }
}

private class NoCache : ResponseCache {
    override suspend fun get(key: String): CachedResponse? = null
    override suspend fun put(key: String, body: String, fetchedAt: Long) = Unit
}

class DiscoverRepositoryTest {
    private val shelvesJson = """{"shelves":[{"id":"popular","label":"Most-loved classics","blurb":"x","ids":[1342,84,11],"kids":false},
        {"id":"kids","label":"Children's favourites","ids":[11,55],"kids":true}],"topics":[{"topic":"x","label":"X"}]}"""

    @Test fun shelvesParseAndIgnoreUnknownFields() {
        val shelves = ApiParser.shelves(shelvesJson)
        assertEquals(listOf("popular", "kids"), shelves.map { it.id })
        assertEquals(listOf(1342, 84, 11), shelves[0].ids)
        assertTrue(shelves[1].kids)
    }

    @Test fun idsRequestIsCappedAtTwentyFour() {
        val p = Endpoints.catalogIds((1..40).toList())
        assertEquals(24, p.single().second.split(",").size)
    }

    @Test fun booksComeBackInTheShelfsOwnOrderAndCopyrightedOnesAreDropped() = runTest {
        val catalog = """{"count":3,"page":1,"pageSize":3,"hasNext":false,"hasPrev":false,"results":[
            {"id":11,"title":"Alice","authors":[{"name":"Carroll, Lewis"}],"copyright":false,"epub":true,"epubUrl":"u"},
            {"id":84,"title":"Frankenstein","authors":[{"name":"Shelley, Mary"}],"copyright":true,"epub":true,"epubUrl":"u"},
            {"id":1342,"title":"Pride","authors":[{"name":"Austen, Jane"}],"copyright":false,"epub":true,"epubUrl":"u"}]}"""
        val repo = SearchRepository(CachedFetcher(MapRemote(mapOf(Endpoints.CATALOG to catalog)), NoCache(), { true }))
        val books = repo.booksByIds(listOf(1342, 84, 11)).value
        assertEquals(listOf("Pride", "Alice"), books.map { it.title })
    }

    @Test fun techFeedParses() = runTest {
        val tech = """{"kind":"ai","items":[{"id":"1","title":"Claude Haiku 5.5","url":"https://x.example","points":1046,"comments":300,"source":"Hacker News"}]}"""
        val repo = SearchRepository(CachedFetcher(MapRemote(mapOf(Endpoints.TECH to tech)), NoCache(), { true }))
        val items = repo.techFeed("ai").value
        assertEquals("Claude Haiku 5.5", items.single().title)
        assertEquals(1046, items.single().points)
    }
}
