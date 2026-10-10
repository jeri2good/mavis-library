package com.mavislibrary.app.data.repo

import com.mavislibrary.app.data.api.ApiException
import com.mavislibrary.app.data.api.RemoteSource
import com.mavislibrary.app.data.db.CachedResponseDao
import com.mavislibrary.app.data.db.CachedResponseEntity

data class CachedResponse(val body: String, val fetchedAt: Long)

interface ResponseCache {
    suspend fun get(key: String): CachedResponse?
    suspend fun put(key: String, body: String, fetchedAt: Long)
}

class RoomResponseCache(private val dao: CachedResponseDao) : ResponseCache {
    override suspend fun get(key: String) = dao.get(key)?.let { CachedResponse(it.body, it.fetchedAt) }
    override suspend fun put(key: String, body: String, fetchedAt: Long) = dao.put(CachedResponseEntity(key, body, fetchedAt))
}

enum class Origin { NETWORK, CACHE_FRESH, CACHE_STALE }

/** A value plus where it came from, so the UI can say honestly when it is showing saved data. */
data class Loaded<T>(val value: T, val origin: Origin, val fetchedAt: Long) {
    val isStale get() = origin == Origin.CACHE_STALE
}

/**
 * Network-first fetch with a raw-JSON cache:
 *  - a cached answer younger than [freshForMs] is used without a request;
 *  - offline, or when the server is unreachable / failing (5xx, 429), the last saved answer is served as stale;
 *  - client errors (4xx such as a bad query) are never masked by the cache.
 */
class CachedFetcher(
    private val remote: RemoteSource,
    private val cache: ResponseCache,
    private val isOnline: () -> Boolean,
    private val clock: () -> Long = System::currentTimeMillis,
    private val freshForMs: Long = 5 * 60_000L,
) {
    suspend fun <T> fetch(path: String, params: List<Pair<String, String>>, parse: (String) -> T): Loaded<T> {
        val key = cacheKey(path, params)
        val cached = cache.get(key)
        val cachedValue = cached?.let { c -> runCatching { parse(c.body) }.getOrNull()?.let { it to c.fetchedAt } }
        val now = clock()

        if (cachedValue != null && now - cachedValue.second in 0 until freshForMs) {
            return Loaded(cachedValue.first, Origin.CACHE_FRESH, cachedValue.second)
        }
        if (!isOnline()) {
            cachedValue?.let { return Loaded(it.first, Origin.CACHE_STALE, it.second) }
            throw ApiException("offline", "You're offline, and this hasn't been saved on the device yet.", offline = true)
        }
        return try {
            val body = remote.get(path, params)
            val value = parse(body)
            cache.put(key, body, now)
            Loaded(value, Origin.NETWORK, now)
        } catch (e: ApiException) {
            val recoverable = e.offline || e.status == 429 || (e.status ?: 0) >= 500
            if (recoverable && cachedValue != null) Loaded(cachedValue.first, Origin.CACHE_STALE, cachedValue.second) else throw e
        }
    }

    companion object {
        fun cacheKey(path: String, params: List<Pair<String, String>>): String =
            path + "?" + params.sortedBy { it.first }.joinToString("&") { (k, v) -> "$k=${v.trim().lowercase()}" }
    }
}
