package com.mavislibrary.app.data.podcast

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okio.Buffer
import okio.buffer
import okio.source
import java.io.IOException
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resumeWithException

class StudioTransport(private val base: String) {
    val client = OkHttpClient.Builder().cookieJar(CookieJar.NO_COOKIES).proxy(Proxy.NO_PROXY)
        .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
        .connectTimeout(15, TimeUnit.SECONDS).readTimeout(120, TimeUnit.SECONDS).build()

    suspend fun execute(request: Request): Response = perform(request, { it }, { it.close() })
    suspend fun <T> consume(request: Request, reader: (Response) -> T): T = perform(request, { it.use(reader) }, {})

    private suspend fun <T> perform(request: Request, handle: (Response) -> T, cancelled: (T) -> Unit): T = withContext(Dispatchers.IO) {
        require(StudioAddress.sameOrigin(base, request.url.toString())) { "Request must stay at the configured studio." }
        if (request.url.scheme == "http") {
            val socket = Socket()
            suspendCancellableCoroutine { continuation ->
                continuation.invokeOnCancellation { socket.close() }
                client.dispatcher.executorService.execute {
                    try {
                        val value = handle(privateHttp(request, socket))
                        continuation.resume(value) { _, result, _ -> cancelled(result) }
                    } catch (e: Exception) {
                        socket.close()
                        if (!continuation.isCancelled) continuation.resumeWithException(e)
                    }
                }
            }
        } else suspendCancellableCoroutine { continuation ->
            val call = client.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (!continuation.isCancelled) continuation.resumeWithException(IOException("Studio offline or unreachable."))
                }
                override fun onResponse(call: Call, response: Response) {
                    try {
                        val value = handle(response)
                        continuation.resume(value) { _, result, _ -> cancelled(result) }
                    } catch (e: Exception) {
                        response.close()
                        if (!continuation.isCancelled) continuation.resumeWithException(e)
                    }
                }
            })
        }
    }

    internal fun privateHttp(request: Request, socket: Socket): Response {
        val url = request.url
        require(StudioAddress.sameOrigin(base, url.toString()) && StudioAddress.privateHost(url.host))
        socket.connect(InetSocketAddress(url.host, url.port), 15_000)
        socket.soTimeout = 120_000
        val body = Buffer().apply { request.body?.writeTo(this) }.readByteArray()
        val path = url.encodedPath + (url.encodedQuery?.let { "?$it" } ?: "")
        val headers = request.headers.newBuilder().set("Host", "${url.host}:${url.port}")
            .set("Connection", "close").set("Accept-Encoding", "identity").set("Content-Length", body.size.toString())
            .apply { request.body?.contentType()?.let { set("Content-Type", it.toString()) } }.build()
        socket.getOutputStream().apply {
            write("${request.method} $path HTTP/1.1\r\n".toByteArray(Charsets.US_ASCII))
            headers.forEach { (name, value) -> write("$name: $value\r\n".toByteArray(Charsets.UTF_8)) }
            write("\r\n".toByteArray()); write(body); flush()
        }
        val source = socket.getInputStream().buffered()
        val status = line(source).split(' ', limit = 3)
        if (status.size < 2 || status[0] !in setOf("HTTP/1.1", "HTTP/1.0")) throw IOException("Invalid studio response.")
        val code = status[1].toIntOrNull() ?: throw IOException("Invalid studio response.")
        val responseHeaders = Headers.Builder()
        var headerBytes = 0
        while (true) {
            val value = line(source)
            if (value.isEmpty()) break
            headerBytes += value.length
            if (headerBytes > 32_768 || !value.contains(':')) throw IOException("Invalid studio headers.")
            responseHeaders.add(value.substringBefore(':'), value.substringAfter(':').trim())
        }
        val h = responseHeaders.build()
        val length = h["Content-Length"]?.toLongOrNull() ?: -1L
        val chunked = h["Transfer-Encoding"]?.equals("chunked", true) == true
        if (h.values("Content-Length").size > 1 || h["Content-Length"] != null && length < 0 || chunked && h["Content-Length"] != null) throw IOException("Ambiguous studio body length.")
        if (h["Transfer-Encoding"] != null && !chunked) throw IOException("Unsupported studio transfer encoding.")
        val stream = FramedStream(source, socket, if (code == 204) 0 else length, chunked)
        return Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(code).message(status.getOrElse(2) { "" }).headers(h)
            .body(object : ResponseBody() {
                private val buffered = stream.source().buffer()
                override fun contentType() = h["Content-Type"]?.toMediaTypeOrNull()
                override fun contentLength() = if (chunked) -1L else length
                override fun source() = buffered
            }).build()
    }

    private class FramedStream(val input: InputStream, val socket: Socket, var remaining: Long, val chunked: Boolean) : InputStream() {
        private var first = true
        private var done = false
        override fun read(): Int = ByteArray(1).let { if (read(it, 0, 1) < 0) -1 else it[0].toInt() and 255 }
        override fun read(bytes: ByteArray, offset: Int, count: Int): Int {
            if (count == 0) return 0
            if (done) return -1
            if (chunked && (first || remaining == 0L)) {
                if (!first && line(input).isNotEmpty()) throw IOException("Invalid chunk boundary.")
                first = false
                remaining = line(input).substringBefore(';').toLongOrNull(16) ?: throw IOException("Invalid chunk size.")
                if (remaining < 0) throw IOException("Invalid chunk size.")
                if (remaining == 0L) { done = true; return -1 }
            }
            if (remaining == 0L) return -1
            val n = input.read(bytes, offset, if (remaining < 0) count else minOf(count.toLong(), remaining).toInt())
            if (n < 0 && remaining > 0) throw IOException("Incomplete studio response.")
            if (n > 0 && remaining > 0) remaining -= n
            return n
        }
        override fun close() { input.close(); socket.close() }
    }

    companion object {
        private fun line(input: InputStream): String {
            val out = java.io.ByteArrayOutputStream()
            while (out.size() < 8192) {
                val b = input.read()
                if (b < 0) throw IOException("Incomplete studio headers.")
                if (b == 10) return out.toString("UTF-8").trimEnd('\r')
                out.write(b)
            }
            throw IOException("Studio header too large.")
        }
    }
}
