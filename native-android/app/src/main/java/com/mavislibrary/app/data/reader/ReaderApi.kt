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
interface ReaderService { suspend fun preview(url: String): SitePreview }
/** Reuses the podcast gateway's adult gate, HTTP client and account/owner headers. */
class ReaderApi(private val gateway: PodcastApi) : ReaderService {
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
    catch (_: Exception) { SiteDiscovery(DiscoveryResult.NotFound(local.reason, "No readable articles found. Try another address.")) }
}
