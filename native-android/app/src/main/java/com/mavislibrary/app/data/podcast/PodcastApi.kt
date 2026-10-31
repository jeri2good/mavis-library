package com.mavislibrary.app.data.podcast

import com.mavislibrary.app.data.api.ApiException
import com.mavislibrary.app.data.api.loungeError
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import kotlinx.serialization.encodeToString
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit

val podcastJson = Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = false }

interface PodcastService {
    suspend fun health(): StudioHealth
    suspend fun create(request: PodcastRequest): PodcastCreated
    suspend fun get(id: String): PodcastJob
    suspend fun list(): List<PodcastJob>
    suspend fun delete(id: String)
}

@Serializable private data class GatewayList(val jobs: List<PodcastJob>, val cursor: String? = null)
@Serializable private data class SignedAudio(val url: String, val expiresAt: String)

class PodcastApi(
    val address: String,
    private val execute: (suspend (Request) -> Response)? = null,
    private val owner: suspend () -> String? = { null },
    private val adult: suspend () -> Boolean = { true },
    private val onCreated: suspend (PodcastCreated, PodcastRequest) -> Unit = { _, _ -> },
    private val token: suspend () -> String?,
) : PodcastService {
    private val client by lazy { OkHttpClient.Builder().followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
        .connectTimeout(15, TimeUnit.SECONDS).readTimeout(120, TimeUnit.SECONDS).build() }
    private fun endpoint(path: String) = StudioAddress.parse(address).newBuilder().encodedPath(path.substringBefore('?')).encodedQuery(path.substringAfter('?', "").ifBlank { null }).build().toString()
    fun audioUrl(id: String) = endpoint("/api/podcast/audio/${StudioAddress.safeId(id)}")
    fun isAudioUrl(url: String) = StudioAddress.sameOrigin(address, url) && url.toHttpUrlOrNull()?.let {
        it.query == null && it.fragment == null && it.encodedPath.matches(Regex("/api/podcast/audio/[A-Za-z0-9_-]{1,100}"))
    } == true
    private suspend fun call(request: Request): Response = withContext(Dispatchers.IO) {
        try { execute?.invoke(request) ?: client.newCall(request).execute() }
        catch (_: IOException) { throw IOException("Studio unavailable, try later") }
    }
    private suspend fun requireAdult() { check(adult()) { "Leave Kids mode to use Podcast Lounge." } }
    internal suspend fun request(path: String, method: String = "GET", body: String? = null): String {
        requireAdult()
        val builder = Request.Builder().url(endpoint(path)).header("Accept", "application/json").header("Cache-Control", "no-store").header("x-mavis-source", "android-native")
        val session = token()?.takeIf(String::isNotBlank)
        val code = owner()?.takeIf(String::isNotBlank)
        if (session == null && code == null) throw ApiException("sign_in_required", "Sign in to use Podcast Lounge", 401)
        session?.let { builder.header("Authorization", "Bearer $it") }
        code?.let { builder.header("x-mavis-access", it) }
        builder.method(method, body?.toRequestBody("application/json".toMediaType()))
        return withContext(Dispatchers.IO) { call(builder.build()).use { response ->
            val source = response.body?.source() ?: throw IOException("Empty gateway response.")
            source.request(1_000_001)
            if (source.buffer.size > 1_000_000) throw IOException("Gateway response too large.")
            val text = source.readUtf8()
            if (!response.isSuccessful) throw loungeError(response.code, text)
            text
        } }
    }
    suspend fun audioRequest(url: String, range: String? = null): Request {
        requireAdult()
        if (!isAudioUrl(url)) throw IOException("Audio must be requested through the Mavis gateway.")
        val id = url.toHttpUrlOrNull()!!.pathSegments.last()
        val signedBody = request("/api/podcast/audio/$id")
        val signed = runCatching { podcastJson.decodeFromString<SignedAudio>(signedBody) }.getOrNull()
            ?: throw IOException("The gateway returned an invalid audio link. Try again.")
        val target = signed.url.toHttpUrlOrNull()
        val expires = runCatching { java.time.Instant.parse(signed.expiresAt) }.getOrNull()
        if (target == null || target.scheme != "https" || target.username.isNotEmpty() || target.password.isNotEmpty() || target.fragment != null ||
            !target.encodedPath.endsWith("/v1/podcasts/$id/audio") || target.queryParameterNames != setOf("exp", "uid", "sig") ||
            target.queryParameterNames.any { target.queryParameterValues(it).size != 1 } || target.queryParameter("sig")?.matches(Regex("[a-f0-9]{64}")) != true ||
            expires == null || !expires.isAfter(java.time.Instant.now())) throw IOException("The gateway returned an invalid or expired audio link. Try again.")
        return Request.Builder().url(target).apply { if (range != null) header("Range", range) }.build()
    }
    suspend fun audio(url: String, range: String? = null): Response {
        val response = call(audioRequest(url, range))
        if (!response.isSuccessful) { response.close(); throw IOException("Studio audio unavailable, try later") }
        return response
    }
    suspend fun copyAudio(id: String, out: java.io.OutputStream) = withContext(Dispatchers.IO) {
        val context = kotlinx.coroutines.currentCoroutineContext()
        audio(audioUrl(id)).use { response ->
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
    override suspend fun health(): StudioHealth {
        val status = podcastJson.parseToJsonElement(request("/api/subscription/status")).jsonObject
        return StudioHealth(status["podcastEnabled"]?.jsonPrimitive?.booleanOrNull == true, StudioEngines(StudioEngine(false), StudioEngine(false)))
    }
    override suspend fun create(request: PodcastRequest): PodcastCreated {
        val fields = podcastJson.encodeToJsonElement(request.validated()).jsonObject.toMutableMap()
        request.gutenbergId?.let { fields["gutenbergId"] = JsonPrimitive(it.toString()) }
        return podcastJson.decodeFromString<PodcastCreated>(request("/api/podcast/create", "POST", JsonObject(fields).toString())).also { onCreated(it, request) }
    }
    override suspend fun get(id: String) = podcastJson.decodeFromString<PodcastJob>(request("/api/podcast/job/${StudioAddress.safeId(id)}"))
    override suspend fun list(): List<PodcastJob> {
        val jobs = mutableListOf<PodcastJob>()
        val seen = mutableSetOf<String>()
        var cursor: String? = null
        do {
            val path = "/api/podcast/list"
            val page = podcastJson.decodeFromString<GatewayList>(requestList(path, cursor))
            jobs += page.jobs
            cursor = page.cursor
            check(cursor == null || seen.add(cursor)) { "Could not finish loading podcasts. Refresh to retry." }
        } while (cursor != null)
        return jobs
    }
    private suspend fun requestList(path: String, cursor: String?): String {
        if (cursor == null) return request(path)
        return request("$path?cursor=${java.net.URLEncoder.encode(cursor, "UTF-8")}")
    }
    override suspend fun delete(id: String) { request("/api/podcast/job/${StudioAddress.safeId(id)}", "DELETE") }
}
