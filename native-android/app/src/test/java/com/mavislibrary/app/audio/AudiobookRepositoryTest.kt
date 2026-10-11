package com.mavislibrary.app.audio

import com.mavislibrary.app.data.api.Endpoints
import com.mavislibrary.app.data.api.RemoteSource
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class AudiobookRepositoryTest {
    @Test fun `find uses native librivox endpoint and parses chapters`() = runTest {
        var request: Pair<String, List<Pair<String, String>>>? = null
        val remote = RemoteSource { path, params ->
            request = path to params
            """{"versions":[{"id":"12","title":"Pride","readers":["Jane"],"sections":[{"n":1,"title":"Chapter 1","url":"https://archive.org/a.mp3","secs":61}]}]}"""
        }
        val result = AudiobookRepository(remote).find("Pride", "Austen", 1342)
        assertEquals(Endpoints.LIBRIVOX, request?.first)
        assertEquals(listOf("title" to "Pride", "author" to "Austen", "gid" to "1342"), request?.second)
        assertEquals("Chapter 1", result.single().sections.single().title)
    }
}
