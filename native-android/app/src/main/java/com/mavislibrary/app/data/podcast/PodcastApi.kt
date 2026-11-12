package com.mavislibrary.app.data.podcast

import com.mavislibrary.app.data.api.ApiException
import com.mavislibrary.app.data.api.loungeError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
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
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resumeWithException

val podcastJson = Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = false }

interface PodcastService {
    suspend fun health(): StudioHealth
    suspend fun create(request: PodcastRequest): PodcastCreated
    suspend fun get(id: String): PodcastJob
    suspend fun list(): List<PodcastJob>
    /** Like [list], and also says whether every page was read. */
    suspend fun listing(): PodcastListing = PodcastListing(list())
    suspend fun delete(id: String)
}

/** Why podcast audio couldn't be opened. The wording is fixed, so it is safe to show and never contains a link. */
enum class AudioProblem(val message: String) {
    NOT_READY("This podcast isn't ready to play yet."),
    GONE("This podcast is no longer at the studio."),
    SIGN_IN("Sign in again to play this podcast."),
    EXPIRED("The audio link ran out. Tap play to try again."),
    OFFLINE("Can't reach the studio. Check your connection, or download podcasts to play them offline."),
    BAD_LINK("The gateway returned an invalid or expired audio link. Try again."),
    RANGE("That position is past the end of this podcast."),
    NOT_AUDIO("Studio did not return MP3 audio."),
    UNAVAILABLE("Studio audio unavailable, try later");
    companion object { fun of(message: String?) = entries.find { it.message == message } }
}
class PodcastAudioException(val problem: AudioProblem, cause: Throwable? = null) : IOException(problem.message, cause)

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
    /** JSON calls: the gateway gives the studio 10 seconds, so nothing here should hang for minutes the way a long audio read may. */
    private val jsonClient by lazy { client.newBuilder().readTimeout(30, TimeUnit.SECONDS).callTimeout(45, TimeUnit.SECONDS).build() }
    private class Signed(val url: okhttp3.HttpUrl, val expires: Instant, val who: Int)
    private val signedLinks = ConcurrentHashMap<String, Signed>()
    private fun endpoint(path: String) = StudioAddress.parse(address).newBuilder().encodedPath(path.substringBefore('?')).encodedQuery(path.substringAfter('?', "").ifBlank { null }).build().toString()
    fun audioUrl(id: String) = endpoint("/api/podcast/audio/${StudioAddress.safeId(id)}")
    fun isAudioUrl(url: String) = StudioAddress.sameOrigin(address, url) && url.toHttpUrlOrNull()?.let {
        it.query == null && it.fragment == null && it.encodedPath.matches(Regex("/api/podcast/audio/[A-Za-z0-9_-]{1,100}"))
    } == true
    private fun unreachable(cause: IOException) = ApiException("offline", "Can't reach Mavis. Check your connection.", offline = true, cause = cause)
    /** Runs one HTTP call. Cancelling the coroutine cancels the call instead of leaving it running. */
    private suspend fun call(request: Request, http: OkHttpClient = client): Response {
        execute?.let { run ->
            return try { withContext(Dispatchers.IO) { run(request) } } catch (e: CancellationException) { throw e } catch (e: IOException) { throw unreachable(e) }
        }
        return suspendCancellableCoroutine { continuation ->
            val call = http.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : okhttp3.Callback {
                override fun onFailure(call: okhttp3.Call, e: IOException) { if (continuation.isActive) continuation.resumeWithException(unreachable(e)) }
                override fun onResponse(call: okhttp3.Call, response: Response) { continuation.resume(response) { _, unused, _ -> unused.close() } }
            })
        }
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
        return withContext(Dispatchers.IO) { call(builder.build(), jsonClient).use { response ->
            val text = try {
                val source = response.body?.source() ?: throw IOException("Empty gateway response.")
                source.request(1_000_001)
                if (source.buffer.size > 1_000_000) throw ApiException("bad_response", "The gateway sent more than this app accepts.")
                source.readUtf8()
            } catch (e: ApiException) { throw e } catch (e: IOException) { throw unreachable(e) }
            if (!response.isSuccessful) throw loungeError(response.code, text)
            text
        } }
    }
    private fun unreadable() = ApiException("bad_response", "The studio sent something this app couldn't read. Try again.")
    /** A job this app can show, or null. Blank titles get a plain name so rows are never empty. */
    private fun job(element: JsonElement): PodcastJob? = runCatching {
        podcastJson.decodeFromJsonElement<PodcastJob>(element).let { StudioAddress.safeId(it.id); it.copy(title = it.title.ifBlank { "Untitled podcast" }) }
    }.getOrNull()
    private suspend fun who() = (token().orEmpty() + "\n" + owner().orEmpty()).hashCode()
    /** Forget the signed link for [id], so the next open asks the gateway for a new one. */
    fun forgetAudioLink(id: String) { signedLinks.remove(id) }
    private suspend fun signedLink(id: String, now: Instant): okhttp3.HttpUrl {
        val who = who()
        // One signed link serves every seek until shortly before it runs out, instead of a gateway round trip per seek.
        signedLinks[id]?.takeIf { it.who == who && it.expires.isAfter(now.plusSeconds(60)) }?.let { return it.url }
        val signedBody = try { request("/api/podcast/audio/$id") } catch (e: ApiException) {
            throw PodcastAudioException(when {
                e.status == 409 -> AudioProblem.NOT_READY
                e.status == 404 -> AudioProblem.GONE
                e.status == 401 || e.status == 403 -> AudioProblem.SIGN_IN
                e.offline -> AudioProblem.OFFLINE
                else -> AudioProblem.UNAVAILABLE
            }, e)
        }
        val signed = runCatching { podcastJson.decodeFromString<SignedAudio>(signedBody) }.getOrNull() ?: throw PodcastAudioException(AudioProblem.BAD_LINK)
        val target = signed.url.toHttpUrlOrNull()
        val expires = runCatching { Instant.parse(signed.expiresAt) }.getOrNull()
        if (target == null || target.scheme != "https" || target.username.isNotEmpty() || target.password.isNotEmpty() || target.fragment != null ||
            !target.encodedPath.endsWith("/v1/podcasts/$id/audio") || target.queryParameterNames != setOf("exp", "uid", "sig") ||
            target.queryParameterNames.any { target.queryParameterValues(it).size != 1 } || target.queryParameter("sig")?.matches(Regex("[a-f0-9]{64}")) != true ||
            expires == null || !expires.isAfter(now)) throw PodcastAudioException(AudioProblem.BAD_LINK)
        signedLinks[id] = Signed(target, expires, who)
        return target
    }
    suspend fun audioRequest(url: String, range: String? = null, now: Instant = Instant.now()): Request {
        requireAdult()
        if (!isAudioUrl(url)) throw IOException("Audio must be requested through the Mavis gateway.")
        val id = url.toHttpUrlOrNull()!!.pathSegments.last()
        return Request.Builder().url(signedLink(id, now)).apply { if (range != null) header("Range", range) }.build()
    }
    suspend fun audio(url: String, range: String? = null): Response {
        var resigned = false
        while (true) {
            val request = audioRequest(url, range)
            val response = try { call(request) } catch (e: ApiException) { throw PodcastAudioException(AudioProblem.OFFLINE, e) }
            if (response.isSuccessful) return response
            val status = response.code
            response.close()
            val id = url.toHttpUrlOrNull()!!.pathSegments.last()
            // The studio refuses a link that ran out (or was signed against a skewed clock): sign once more, then give up.
            if (status == 401 || status == 403) {
                forgetAudioLink(id)
                if (!resigned) { resigned = true; continue }
                throw PodcastAudioException(AudioProblem.EXPIRED)
            }
            throw PodcastAudioException(when (status) { 404, 410 -> AudioProblem.GONE; 416 -> AudioProblem.RANGE; else -> AudioProblem.UNAVAILABLE })
        }
    }
    suspend fun copyAudio(id: String, out: java.io.OutputStream) = withContext(Dispatchers.IO) {
        val context = kotlinx.coroutines.currentCoroutineContext()
        audio(audioUrl(id)).use { response ->
            val body = response.body ?: throw IOException("Audio download was empty.")
            if (body.contentType()?.toString()?.substringBefore(';') !in setOf("audio/mpeg", "audio/mp3")) throw PodcastAudioException(AudioProblem.NOT_AUDIO)
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
    override suspend fun get(id: String): PodcastJob {
        val body = request("/api/podcast/job/${StudioAddress.safeId(id)}")
        return runCatching { podcastJson.parseToJsonElement(body) }.getOrNull()?.let(::job) ?: throw unreadable()
    }
    override suspend fun list(): List<PodcastJob> = listing().jobs
    /**
     * Reads every page. A job this app can't decode is skipped rather than failing the list, and trouble after the
     * first page (a repeated cursor, a failed page, too many pages) keeps what was read and reports the list as incomplete.
     */
    override suspend fun listing(): PodcastListing {
        val jobs = LinkedHashMap<String, PodcastJob>()
        val seen = mutableSetOf<String>()
        var cursor: String? = null
        var skipped = 0
        var pages = 0
        while (true) {
            val body = try {
                request(if (cursor == null) "/api/podcast/list" else "/api/podcast/list?cursor=${java.net.URLEncoder.encode(cursor, "UTF-8")}")
            } catch (e: ApiException) {
                if (pages == 0 || e.status == 401 || e.status == 403) throw e
                return PodcastListing(jobs.values.toList(), complete = false, skipped = skipped)
            }
            val page = runCatching { podcastJson.parseToJsonElement(body).jsonObject }.getOrNull()
            val rows = runCatching { page?.get("jobs")?.jsonArray }.getOrNull()
            if (page == null || rows == null) {
                if (pages == 0) throw unreadable()
                return PodcastListing(jobs.values.toList(), complete = false, skipped = skipped)
            }
            pages++
            rows.forEach { row -> job(row)?.let { jobs[it.id] = it } ?: skipped++ }
            cursor = runCatching { page["cursor"]?.jsonPrimitive?.contentOrNull }.getOrNull()?.takeIf(String::isNotBlank)
            if (cursor == null) return PodcastListing(jobs.values.toList(), complete = true, skipped = skipped)
            if (!seen.add(cursor) || pages >= MAX_LIST_PAGES) return PodcastListing(jobs.values.toList(), complete = false, skipped = skipped)
        }
    }
    override suspend fun delete(id: String) { request("/api/podcast/job/${StudioAddress.safeId(id)}", "DELETE") }
    private companion object { const val MAX_LIST_PAGES = 50 }
}
