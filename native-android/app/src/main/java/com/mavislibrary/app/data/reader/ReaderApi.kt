package com.mavislibrary.app.data.reader

import com.mavislibrary.app.data.podcast.PodcastApi
import com.mavislibrary.app.data.podcast.podcastJson
import com.mavislibrary.app.data.feeds.*
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import java.net.URLEncoder

@Serializable data class PreviewPost(val title: String, val link: String)
@Serializable data class SitePreview(val source: String, val feedUrl: String, val siteUrl: String,
    val title: String, val posts: List<PreviewPost>) {
    fun found() = DiscoveryResult.Found(UrlPolicy.normalize(feedUrl), title, posts.size, null, UrlPolicy.normalize(siteUrl))
    fun validated(): SitePreview {
        require(source in setOf("feed", "page") && posts.size >= 3)
        found()
        posts.forEach { UrlPolicy.normalize(it.link) }
        return this
    }
}
@Serializable data class PagePost(val id: String, val title: String, val link: String, val publishedAt: Long)
@Serializable data class PagePosts(val posts: List<PagePost>)
@Serializable data class ReaderArticle(val title: String = "", val byline: String? = null, val siteName: String? = null,
    val image: String? = null, val text: String, val words: Int = 0, val readMin: Int = 1, val url: String = "") {
    fun validated(requested: String): ReaderArticle {
        require(text.isNotBlank()) { "Article unavailable" }
        return copy(url = runCatching { UrlPolicy.normalize(url) }.getOrElse { requested },
            image = image?.takeIf { it.startsWith("https://") }, readMin = readMin.coerceAtLeast(1))
    }
    val paragraphs get() = text.split(Regex("\n\\s*\n")).map(String::trim).filter(String::isNotEmpty)
}
interface ReaderService {
    suspend fun preview(url: String): SitePreview
    suspend fun page(url: String): List<PagePost> = error("Page loading unavailable")
    suspend fun article(url: String): ReaderArticle = error("Article unavailable")
}
/** Reuses the podcast gateway's adult gate, HTTP client and account/owner headers. */
class ReaderApi(private val gateway: PodcastApi) : ReaderService {
    override suspend fun article(url: String): ReaderArticle {
        val normalized = UrlPolicy.normalize(url)
        return podcastJson.decodeFromString<ReaderArticle>(
            gateway.request("/api/podsub/article?url=" + URLEncoder.encode(normalized, "UTF-8"))).validated(normalized)
    }
    override suspend fun page(url: String) = podcastJson.decodeFromString<PagePosts>(
        gateway.request("/api/podsub/page?url=" + URLEncoder.encode(UrlPolicy.normalize(url), "UTF-8"))).posts.take(30).map {
            it.copy(link = UrlPolicy.normalize(it.link))
        }
    override suspend fun preview(url: String) = podcastJson.decodeFromString<SitePreview>(
        gateway.request("/api/podsub/preview?url=" + URLEncoder.encode(UrlPolicy.normalize(url), "UTF-8"))).validated()
}
data class SiteDiscovery(val result: DiscoveryResult, val preview: SitePreview? = null)
suspend fun discoverSite(input: String, discovery: FeedDiscovery, reader: ReaderService?): SiteDiscovery {
    val local = discovery.discover(input)
    if (local !is DiscoveryResult.NotFound || local.reason !in setOf(DiscoveryResult.Reason.NO_FEED,
            DiscoveryResult.Reason.NOT_A_FEED, DiscoveryResult.Reason.BLOCKED) || reader == null) return SiteDiscovery(local)
    return try {
        val preview = reader.preview(input).validated()
        SiteDiscovery(preview.found(), preview)
    } catch (e: CancellationException) { throw e }
    catch (e: Exception) { SiteDiscovery(DiscoveryResult.NotFound(local.reason,
        if (needsOwnerCode(e)) OWNER_CODE_NOTE else "No readable articles found. Try another address.")) }
}
