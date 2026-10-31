package com.mavislibrary.app.data.podcast

import kotlinx.serialization.Serializable
import java.io.IOException
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import com.mavislibrary.app.data.kids.adultRoutesAllowed

const val MAX_PODCAST_TEXT = 400_000
val podcastStyles = linkedMapOf("deep-dive" to "Deep dive", "debate" to "Debate", "critique" to "Critique")
val podcastLengths = linkedMapOf("short" to "Short ~8 min", "default" to "Standard ~14", "long" to "Long ~22")

@Serializable
data class PodcastRequest(
    val kind: String, val title: String, val text: String? = null, val url: String? = null,
    val gutenbergId: Int? = null, val query: String? = null,
    val style: String = "deep-dive", val length: String = "default", val language: String = "en", val engine: String = "notebooklm",
) {
    fun validated(): PodcastRequest {
        require(kind in setOf("book", "chapter", "article", "topic") && title.isNotBlank()) { "Choose a subject and title." }
        require((style in podcastStyles || style == "brief") && length in podcastLengths && engine in setOf("auto", "notebooklm", "local") && language == "en")
        require(title.codePointCount(0, title.length) <= 300) { "Use a title of 300 characters or less." }
        require(text == null || text.codePointCount(0, text.length) <= MAX_PODCAST_TEXT) { "Text must be 400,000 characters or less." }
        require(query == null || query.codePointCount(0, query.length) <= 300) { "Use a topic of 300 characters or less." }
        require(gutenbergId == null || gutenbergId in 1..9_999_999) { "Choose a valid Gutenberg book." }
        if (url != null) { require(url.length <= 2048) { "Use an article URL of 2,048 characters or less." }; articleUrl(url) }
        require(when (kind) {
            "book" -> (gutenbergId != null && gutenbergId > 0) || !text.isNullOrBlank() || url != null
            "chapter" -> !text.isNullOrBlank()
            "article" -> !url.isNullOrBlank()
            else -> !query.isNullOrBlank()
        }) { "Add the source for this podcast." }
        return this
    }
}
fun podcastText(text: String): String = text.take(MAX_PODCAST_TEXT)
fun podcastExpired(job: PodcastJob, days: Int, now: java.time.Instant = java.time.Instant.now()) = days > 0 && job.status == "ready" && runCatching {
    java.time.Instant.parse(job.createdAt).isBefore(now.minusSeconds(days * 86400L))
}.getOrDefault(false)
fun articleUrl(input: String): String {
    val url = input.trim().toHttpUrlOrNull()
    require(url != null && url.scheme == "https" && url.username.isEmpty() && url.password.isEmpty() && !input.substringBefore("://").contains('@') && !input.substringAfter("://").substringBefore('/').contains('@')) { "Paste an HTTPS article address without credentials." }
    return url.toString()
}
fun loungeRouteAllowed(kidsMode: Boolean?) = kidsMode != null && adultRoutesAllowed(kidsMode)

@Serializable data class PodcastCreated(val id: String, val status: String, val queuePosition: Int? = null, val etaMin: Int? = null)
@Serializable data class PodcastSource(val title: String, val url: String? = null)
/** An article a briefing episode covers, in the order the hosts discuss them. */
@Serializable data class PodcastStory(val title: String, val site: String = "", val link: String? = null)
@Serializable data class DevotionalMeta(val devotional: String, val kind: String, val reference: String, val topic: String? = null)
@Serializable data class PodcastJob(
    val id: String, val status: String, val progress: Double = 0.0, val stage: String = "",
    val engine: String = "", val title: String, val kind: String, val style: String,
    val durationSec: Int? = null, val createdAt: String = "", val error: String? = null,
    val transcript: String? = null, val sources: List<PodcastSource> = emptyList(),
    val queuePosition: Int? = null, val etaMin: Int? = null,
    val sites: List<String> = emptyList(), val stories: List<PodcastStory> = emptyList(), val meta: DevotionalMeta? = null,
) {
    val terminal get() = status in setOf("ready", "failed")
    val engineLabel get() = when (engine) { "notebooklm" -> "Made with NotebookLM"; "local" -> "Made with local voices"; else -> "Engine not reported yet" }
}
@Serializable data class PodcastList(val items: List<PodcastJob>)
@Serializable data class PodcastError(val detail: String)
@Serializable data class StudioEngine(val available: Boolean, val loggedIn: Boolean = false, val note: String = "")
@Serializable data class StudioEngines(val notebooklm: StudioEngine, val local: StudioEngine)
@Serializable data class StudioHealth(val ok: Boolean, val engines: StudioEngines) {
    val label get() = when {
        !ok -> "Studio offline"
        engines.notebooklm.available && engines.notebooklm.loggedIn -> "NotebookLM ready"
        engines.local.available -> "Local voices"
        else -> "Studio online · no engine ready"
    }
}
@Serializable data class SavedPodcast(val job: PodcastJob, val studio: String, val localFile: String? = null, val request: PodcastRequest? = null, val accountId: String? = null) {
    val key get() = "$studio/${job.id}"
    val resumeKey get() = "podcast:$key"
}

object StudioAddress {
    fun parse(input: String): HttpUrl {
        val raw = input.trim().trimEnd('/')
        val url = raw.toHttpUrlOrNull() ?: throw IOException("Enter a full studio address (https:// or private http://).")
        if (url.username.isNotEmpty() || url.password.isNotEmpty() || raw.substringAfter("://").contains('@') || url.encodedPath != "/" || url.query != null || url.fragment != null || url.host.endsWith('.')) throw IOException("Use only the studio address, without a path or credentials.")
        if (url.scheme == "http" && !privateHost(url.host)) throw IOException("HTTP is only for a private LAN or Tailscale IP address.")
        return url
    }
    fun privateHost(host: String): Boolean {
        val parts = host.split('.').map { it.toIntOrNull() ?: return false }
        if (parts.size != 4 || parts.any { it !in 0..255 } || parts.joinToString(".") != host) return false
        return parts[0] == 10 || parts[0] == 172 && parts[1] in 16..31 || parts[0] == 192 && parts[1] == 168 || parts[0] == 100 && parts[1] in 64..127
    }
    fun sameOrigin(base: String, target: String): Boolean = runCatching {
        val a = parse(base)
        val b = target.toHttpUrlOrNull() ?: return false
        a.scheme == b.scheme && a.host == b.host && a.port == b.port && b.username.isEmpty() && b.password.isEmpty()
    }.getOrDefault(false)
    fun audioAllowed(base: String, target: String) = sameOrigin(base, target) && target.toHttpUrlOrNull()?.let {
        it.query == null && it.fragment == null && it.encodedPath.matches(Regex("/v1/podcasts/[A-Za-z0-9_-]{1,128}/audio"))
    } == true
    fun safeId(id: String): String {
        require(id.matches(Regex("[A-Za-z0-9_-]{1,100}"))) { "Invalid podcast ID." }
        return id
    }
}
