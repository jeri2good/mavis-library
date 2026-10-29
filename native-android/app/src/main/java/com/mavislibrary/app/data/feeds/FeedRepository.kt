package com.mavislibrary.app.data.feeds

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException

 data class FeedLoad(val stories: List<FeedStory>, val note: String? = null)

/** Fifteen-minute process cache. Offline cache survives navigation, but not process death. */
class FeedRepository(private val fetcher: FeedFetcher, private val online: () -> Boolean, private val clock: () -> Long = System::currentTimeMillis, private val reader: com.mavislibrary.app.data.reader.ReaderService? = null) {
    private data class Cached(val at: Long, val feed: ParsedFeed)
    private val cache = mutableMapOf<String, Cached>()
    private val mutex = Mutex()
    suspend fun load(sources: List<FeedSource>): FeedLoad = mutex.withLock {
        val notes = mutableListOf<String>()
        val stories = sources.filter { it.enabled }.flatMap { source ->
            val old = cache["${source.source}:${source.feedUrl}"]
            val feed = if (!online()) {
                notes += if (old == null) "Offline — no cached stories for ${source.name}." else "Offline — showing cached stories."
                old?.feed
            } else if (old != null && clock() - old.at < 15 * 60_000) old.feed
            else try {
                val parsed = if (source.source == "page") {
                    val posts = checkNotNull(reader) { "Page service unavailable" }.page(source.feedUrl)
                    val stories = posts.map { FeedStory(com.mavislibrary.app.data.api.TechItem(it.id, it.title, it.link), it.publishedAt) }
                    ParsedFeed(source.name, stories, stories.size, stories.maxOfOrNull { it.published ?: 0 })
                } else {
                    val response = fetcher.fetch(source.feedUrl)
                    if (response.status !in 200..299) throw IOException("HTTP ${response.status}")
                    FeedParser.parse(response.body)
                }
                parsed.also {
                    if (cache.size >= 20 && "${source.source}:${source.feedUrl}" !in cache) cache.remove(cache.keys.first())
                    cache["${source.source}:${source.feedUrl}"] = Cached(clock(), it)
                }
            } catch (e: CancellationException) { throw e } catch (_: Exception) {
                notes += if (old != null) "Couldn't refresh ${source.name} — showing cached stories." else "Couldn't load ${source.name}. Try again when connected."
                old?.feed
            }
            feed?.stories.orEmpty().map { it.copy(item = it.item.copy(source = source.name)) }
        }.distinctBy { it.item.url }.sortedByDescending { it.published ?: Long.MIN_VALUE }
        FeedLoad(stories, notes.distinct().joinToString(" ").takeIf { it.isNotEmpty() })
    }
}
