package com.mavislibrary.app.data.api

import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class MavisApiTest {
    private val server = MockWebServer()
    private lateinit var api: MavisApi

    @Before
    fun setUp() {
        server.start()
        api = MavisApi(OkHttpClient(), server.url("/").toString())
    }

    @After
    fun tearDown() = server.shutdown()

    @Test
    fun sendsEncodedQueryAndReturnsRawBody() = runTest {
        server.enqueue(MockResponse().setBody("""{"results":[]}"""))
        val body = api.get(Endpoints.CATALOG, Endpoints.catalogSearch("war & peace"))
        assertEquals("""{"results":[]}""", body)
        val req = server.takeRequest()
        assertEquals("/api/catalog", req.requestUrl!!.encodedPath)
        assertEquals("war & peace", req.requestUrl!!.queryParameter("search"))
        assertEquals("application/json", req.getHeader("accept"))
    }

    @Test
    fun httpErrorCarriesServerMessage() = runTest {
        server.enqueue(MockResponse().setResponseCode(400).setBody("""{"error":"bad_request","message":"q is required."}"""))
        val e = runCatching { api.get(Endpoints.OPEN_LIBRARY, listOf("q" to "")) }.exceptionOrNull() as ApiException
        assertEquals(400, e.status)
        assertEquals("bad_request", e.code)
        assertEquals("q is required.", e.message)
    }

    @Test
    fun unreachableServerIsOffline() = runTest {
        server.shutdown()
        val e = runCatching { api.get(Endpoints.CATALOG, emptyList()) }.exceptionOrNull() as ApiException
        assertTrue(e.offline)
    }
}
