package com.mavislibrary.app.data.feeds

import kotlinx.coroutines.CancellationException
import java.net.URI

sealed interface DiscoveryResult {
    data class Found(val feedUrl: String, val title: String, val items: Int, val newest: Long?, val siteUrl: String) : DiscoveryResult
    data class NotFound(val reason: Reason, val message: String) : DiscoveryResult
    enum class Reason { UNREACHABLE, BLOCKED, NO_FEED, NOT_A_FEED }
}

class FeedDiscovery(private val fetcher: FeedFetcher) {
    suspend fun discover(input: String): DiscoveryResult {
        val url = try { UrlPolicy.normalize(input) } catch (e: Exception) {
            return DiscoveryResult.NotFound(DiscoveryResult.Reason.UNREACHABLE, e.message ?: "Enter a valid HTTPS address.")
        }
        val page = try { fetcher.fetch(url) } catch (e: CancellationException) { throw e } catch (_: Exception) {
            return DiscoveryResult.NotFound(DiscoveryResult.Reason.UNREACHABLE, "Couldn't reach this site. Check the address and your connection.")
        }
        if (page.status in listOf(401, 403, 429)) return blocked()
        if (page.status !in 200..299) return DiscoveryResult.NotFound(DiscoveryResult.Reason.UNREACHABLE, "This address couldn't be loaded (HTTP ${page.status}).")
        parsed(page, url)?.let { return it }
        if (Regex("^\\s*(?:<\\?xml[^>]*>\\s*)?<(?:rss|feed|rdf:RDF)\\b", RegexOption.IGNORE_CASE).containsMatchIn(page.body)) {
            return DiscoveryResult.NotFound(DiscoveryResult.Reason.NOT_A_FEED, "This feed contains invalid or unsupported XML.")
        }
        var blocked = false
        var best: DiscoveryResult.Found? = null
        var bestScore = -1
        for (candidate in candidates(page.url, page.body)) {
            try {
                val response = fetcher.fetch(candidate)
                if (response.status in listOf(401, 403, 429)) blocked = true
                parsed(response, url)?.let { found ->
                    val feed = FeedParser.parse(response.body)
                    val host = URI(url).host.removePrefix("www.")
                    val matchesSite = feed.stories.count { URI(it.item.url).host.removePrefix("www.") == host }
                    val score = matchesSite * 100 + feed.stories.size
                    if (score > bestScore) { best = found; bestScore = score }
                    // A populated feed linking back to the requested site is a strong match.
                    if (matchesSite > 0) return found
                }
            } catch (e: CancellationException) { throw e } catch (_: Exception) { /* Try the next candidate. */ }
        }
        best?.let { return it }
        return if (blocked) blocked() else DiscoveryResult.NotFound(DiscoveryResult.Reason.NO_FEED, "No feed found. Try pasting the site's RSS or Atom feed link.")
    }

    private fun blocked() = DiscoveryResult.NotFound(DiscoveryResult.Reason.BLOCKED, "The site blocks apps or is limiting requests. Try a direct feed link or try again later.")
    private fun parsed(response: FeedResponse, site: String): DiscoveryResult.Found? {
        if (response.status !in 200..299) return null
        val feed = runCatching { FeedParser.parse(response.body) }.getOrNull() ?: return null
        val safe = runCatching { UrlPolicy.normalize(response.url) }.getOrNull() ?: return null
        return DiscoveryResult.Found(safe, feed.title, feed.itemCount, feed.newest, site)
    }

    internal fun candidates(base: String, html: String): List<String> {
        fun resolve(raw: String): String? = runCatching {
            UrlPolicy.normalize(URI(base).resolve(raw.replace("&amp;", "&").replace("\\/", "/")).toString())
        }.getOrNull()
        val alternate = Regex("<link\\b[^>]*>", RegexOption.IGNORE_CASE).findAll(html).mapNotNull { tag ->
            val attrs = Regex("([\\w-]+)\\s*=\\s*(['\"])(.*?)\\2", RegexOption.DOT_MATCHES_ALL).findAll(tag.value)
                .associate { it.groupValues[1].lowercase() to it.groupValues[3] }
            if (attrs["rel"]?.lowercase()?.split(' ')?.contains("alternate") == true &&
                attrs["type"]?.lowercase() in listOf("application/rss+xml", "application/atom+xml", "application/rdf+xml")) attrs["href"]?.let(::resolve) else null
        }.toList()
        val decoded = html.replace("\\/", "/").replace("&amp;", "&")
        val scan = (Regex("(?:href|src)\\s*=\\s*['\"]([^'\"]+)['\"]", RegexOption.IGNORE_CASE).findAll(decoded).map { it.groupValues[1] } +
            Regex("https://[^\\s<>\"'\\\\]+", RegexOption.IGNORE_CASE).findAll(decoded).map { it.value })
            .filter { Regex("rss|feed|atom|\\.xml", RegexOption.IGNORE_CASE).containsMatchIn(it) }
            .mapNotNull(::resolve).distinct().sortedBy { candidate ->
                val uri = URI(candidate)
                when {
                    uri.host == "rss.beehiiv.com" -> 0
                    uri.path == "/feed" -> 1
                    uri.path == "/rss" -> 2
                    uri.path.endsWith(".xml") -> 3
                    else -> 4
                }
            }.take(8).toList()
        val youtube = if (URI(base).host in listOf("youtube.com", "www.youtube.com", "m.youtube.com")) {
            Regex("UC[A-Za-z0-9_-]{22}").find(URI(base).path)?.value
                ?: Regex("(?:channelId|externalId)[\"'\\s:=]+(UC[A-Za-z0-9_-]{22})").find(decoded)?.groupValues?.get(1)
        } else null
        val common = listOf("/feed", "/rss", "/rss.xml", "/atom.xml", "/feed.xml", "/index.xml").mapNotNull(::resolve)
        return (alternate.take(8) + listOfNotNull(youtube?.let { "https://www.youtube.com/feeds/videos.xml?channel_id=$it" }) + scan + common).distinct().filter { it != base }
    }
}
