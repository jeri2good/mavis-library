package com.mavislibrary.app.data.podcast

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException

val podcastJson = Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = false }

interface PodcastService {
    suspend fun health(): StudioHealth
    suspend fun create(request: PodcastRequest): PodcastCreated
    suspend fun get(id: String): PodcastJob
    suspend fun list(): List<PodcastJob>
    suspend fun delete(id: String)
}

class PodcastApi(val address: String, private val execute: (suspend (Request) -> Response)? = null, private val token: suspend () -> String?) : PodcastService {
    private val transport by lazy { StudioTransport(address) }
    private suspend fun <T> consume(request: Request, reader: (Response) -> T): T = if (execute == null) transport.consume(request, reader)
        else withContext(Dispatchers.IO) { execute.invoke(request).use(reader) }
    private fun endpoint(path: String) = StudioAddress.parse(address).newBuilder().encodedPath(path).build().toString()
    fun audioUrl(id: String) = endpoint("/v1/podcasts/${StudioAddress.safeId(id)}/audio")
    suspend fun audioRequest(url: String, range: String? = null): Request {
        if (!StudioAddress.audioAllowed(address, url)) throw IOException("Audio must come from the configured studio.")
        return Request.Builder().url(url).header("Authorization", "Bearer ${key()}").apply { if (range != null) header("Range", range) }.build()
    }
    suspend fun audio(url: String, range: String? = null): Response {
        val request = audioRequest(url, range)
        val response = execute?.invoke(request) ?: transport.execute(request)
        if (!response.isSuccessful) { response.close(); throw IOException("Audio returned HTTP ${response.code}. Redirects are not followed.") }
        return response
    }
    suspend fun copyAudio(id: String, out: java.io.OutputStream) {
        val request = audioRequest(audioUrl(id))
        val context = kotlinx.coroutines.currentCoroutineContext()
        consume(request) { response ->
            if (!response.isSuccessful) throw IOException("Audio returned HTTP ${response.code}. Redirects are not followed.")
            val body = response.body ?: throw IOException("Audio download was empty.")
            if (body.contentType()?.toString()?.substringBefore(';') !in setOf("audio/mpeg", "audio/mp3")) throw IOException("Studio did not return MP3 audio.")
            var total = 0L
            body.byteStream().use { input ->
                val buffer = ByteArray(8192)
                while (true) {
                    context.ensureActive()
                    val n = input.read(buffer)
                    if (n < 0) break
                    total += n
                    if (total > 250L * 1024 * 1024) throw IOException("Podcast exceeds the 250 MB offline limit.")
                    out.write(buffer, 0, n)
                }
            }
            if (total == 0L || body.contentLength() >= 0 && total != body.contentLength()) throw IOException("Incomplete podcast download.")
        }
    }
    private suspend fun key() = token()?.takeIf { it.isNotBlank() && it.length <= 4096 && it.all { char -> char.code in 33..126 } }
        ?: throw IOException("Add your podcast studio key in Settings.")
    private suspend fun request(path: String, method: String = "GET", body: String? = null, authorized: Boolean = true): String = withContext(Dispatchers.IO) {
        val builder = Request.Builder().url(endpoint(path))
        if (authorized) builder.header("Authorization", "Bearer ${key()}")
        builder.method(method, body?.toRequestBody("application/json".toMediaType()))
        consume(builder.build()) { response ->
            val source = response.body?.source() ?: throw IOException("Empty studio response.")
            source.request(4_000_001)
            if (source.buffer.size > 4_000_000) throw IOException("Studio response too large.")
            val text = source.readUtf8()
            if (response.code == 422) throw IOException(runCatching { podcastJson.decodeFromString<PodcastError>(text).detail }.getOrDefault("The studio couldn't use that source."))
            if (!response.isSuccessful) throw IOException("Studio returned HTTP ${response.code}. Redirects are not followed.")
            text
        }
    }
    override suspend fun health() = podcastJson.decodeFromString<StudioHealth>(request("/v1/health", authorized = false))
    override suspend fun create(request: PodcastRequest) = podcastJson.decodeFromString<PodcastCreated>(request("/v1/podcasts", "POST", podcastJson.encodeToString(request.validated())))
    override suspend fun get(id: String) = podcastJson.decodeFromString<PodcastJob>(request("/v1/podcasts/${StudioAddress.safeId(id)}"))
    override suspend fun list() = podcastJson.decodeFromString<PodcastList>(request("/v1/podcasts")).items
    override suspend fun delete(id: String) { request("/v1/podcasts/${StudioAddress.safeId(id)}", "DELETE") }
}
