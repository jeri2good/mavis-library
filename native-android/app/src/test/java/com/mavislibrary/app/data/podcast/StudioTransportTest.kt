package com.mavislibrary.app.data.podcast

import okhttp3.Request
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.Socket
import java.net.SocketAddress
import java.net.InetSocketAddress

private class MemorySocket(response: String) : Socket() {
    val output = ByteArrayOutputStream()
    val input = ByteArrayInputStream(response.toByteArray())
    var target: SocketAddress? = null
    var connectTimeout = 0
    var readTimeout = 0
    var didClose = false
    override fun connect(endpoint: SocketAddress, timeout: Int) { target = endpoint; connectTimeout = timeout }
    override fun setSoTimeout(timeout: Int) { readTimeout = timeout }
    override fun getOutputStream() = output
    override fun getInputStream() = input
    override fun close() { didClose = true }
}
class StudioTransportTest {
    private val base = "http://100.98.33.49:8140"
    @Test fun scopedPrivateHttpFramesRequestAndLengthBodyWithoutRealNetwork() {
        val socket = MemorySocket("HTTP/1.1 202 Accepted\r\nContent-Type: application/json\r\nContent-Length: 2\r\n\r\n{}")
        val request = Request.Builder().url("$base/v1/podcasts").header("Authorization", "Bearer fixture-key").post("{}".toRequestBody("application/json".toMediaType())).build()
        StudioTransport(base).privateHttp(request, socket).use { assertEquals(202, it.code); assertEquals("{}", it.body!!.string()) }
        val wire = socket.output.toString("UTF-8")
        assertTrue(wire.startsWith("POST /v1/podcasts HTTP/1.1\r\n")); assertTrue(wire.contains("Authorization: Bearer fixture-key\r\n"))
        assertTrue(wire.contains("Connection: close\r\n")); assertTrue(wire.contains("Content-Length: 2\r\n"))
        assertTrue(wire.contains("Content-Type: application/json; charset=utf-8\r\n"))
        assertEquals("100.98.33.49", (socket.target as InetSocketAddress).address.hostAddress)
        assertEquals(15_000, socket.connectTimeout); assertEquals(120_000, socket.readTimeout); assertTrue(socket.didClose)
    }
    @Test fun chunkedAudioIsDecodedAndPartialBodyFails() {
        val transport = StudioTransport(base); val request = Request.Builder().url("$base/v1/podcasts/job1/audio").build()
        val chunks = MemorySocket("HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\nContent-Type: audio/mpeg\r\n\r\n3\r\nabc\r\n2\r\nde\r\n0\r\n\r\n")
        transport.privateHttp(request, chunks).use { assertEquals("abcde", it.body!!.string()) }
        val incomplete = MemorySocket("HTTP/1.1 200 OK\r\nContent-Length: 6\r\n\r\nabc")
        transport.privateHttp(request, incomplete).use { assertTrue(runCatching { it.body!!.string() }.isFailure) }
    }
    @Test fun privateTransportCannotDialAnyOtherHostOrPublicHttp() {
        val socket = MemorySocket("")
        assertTrue(runCatching { StudioTransport(base).privateHttp(Request.Builder().url("http://10.0.2.2:8140/v1/health").build(), socket) }.isFailure)
        assertNull(socket.target)
        assertTrue(runCatching { StudioTransport("http://example.org").privateHttp(Request.Builder().url("http://example.org/v1/health").build(), socket) }.isFailure)
        assertNull(socket.target)
    }
}
