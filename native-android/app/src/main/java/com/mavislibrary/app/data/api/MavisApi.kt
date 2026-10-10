package com.mavislibrary.app.data.api

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

/** A failure the UI can show as-is. [offline] means the request never reached the server. */
class ApiException(
    val code: String,
    override val message: String,
    val status: Int? = null,
    val offline: Boolean = false,
    cause: Throwable? = null,
) : IOException(message, cause)

/** Fetches raw JSON so the repository can cache exactly what the server sent. */
fun interface RemoteSource {
    suspend fun get(path: String, params: List<Pair<String, String>>): String
}

class MavisApi(
    private val client: OkHttpClient,
    baseUrl: String,
) : RemoteSource {
    private val base: HttpUrl = baseUrl.toHttpUrl()

    override suspend fun get(path: String, params: List<Pair<String, String>>): String = withContext(Dispatchers.IO) {
        val url = base.newBuilder().encodedPath(path).apply { params.forEach { (k, v) -> addQueryParameter(k, v) } }.build()
        val request = Request.Builder().url(url).header("accept", "application/json").get().build()
        val response = try {
            client.newCall(request).execute()
        } catch (e: IOException) {
            throw ApiException("unreachable", "Could not reach Mavis Library. Check your connection.", offline = true, cause = e)
        }
        response.use { res ->
            val body = res.body?.string().orEmpty()
            if (!res.isSuccessful) throw errorFrom(res.code, body)
            body
        }
    }

    companion object {
        fun errorFrom(status: Int, body: String): ApiException {
            val parsed = runCatching { ApiJson.decodeFromString(ApiErrorBody.serializer(), body) }.getOrNull()
            val message = parsed?.message?.takeIf { it.isNotBlank() } ?: when (status) {
                429 -> "Too many requests. Wait a minute and try again."
                in 500..599 -> "The library server had a problem. Try again shortly."
                else -> "The request failed ($status)."
            }
            return ApiException(parsed?.error ?: "http_$status", message, status)
        }
    }
}

/** Endpoint definitions: query parameters exactly as the handlers accept them. */
object Endpoints {
    const val CATALOG = "/api/catalog"
    const val OPEN_LIBRARY = "/api/openlibrary"

    /** catalog.mjs parseListParams: search (≤120 chars), topic, languages, page 1..2000, sort popular|ascending|descending. */
    fun catalogSearch(search: String, page: Int = 1, languages: String = "", sort: String = "popular", topic: String = ""): List<Pair<String, String>> =
        buildList {
            if (search.isNotBlank()) add("search" to search.trim().take(120))
            if (topic.isNotBlank()) add("topic" to topic.trim().take(60))
            if (languages.isNotBlank()) add("languages" to languages)
            add("page" to page.coerceIn(1, 2000).toString())
            add("sort" to sort)
        }

    fun catalogBook(id: Int) = listOf("id" to id.toString())

    /** openlibrary.mjs: q required (≤120 chars), page 1..100, optional lang (3 letters). */
    fun openLibrarySearch(q: String, page: Int = 1, lang: String = ""): List<Pair<String, String>> = buildList {
        add("q" to q.trim().take(120))
        add("page" to page.coerceIn(1, 100).toString())
        if (lang.isNotBlank()) add("lang" to lang)
    }
}

object ApiParser {
    fun catalogPage(json: String): CatalogPage = decode { ApiJson.decodeFromString(CatalogPage.serializer(), json) }
    fun catalogBook(json: String): GutenbergBook = decode { ApiJson.decodeFromString(CatalogBookResponse.serializer(), json).book }
    fun openLibraryPage(json: String): OpenLibraryPage = decode { ApiJson.decodeFromString(OpenLibraryPage.serializer(), json) }

    private inline fun <T> decode(block: () -> T): T = try {
        block()
    } catch (e: SerializationException) {
        throw ApiException("bad_response", "The library server sent something unexpected.", cause = e)
    } catch (e: IllegalArgumentException) {
        throw ApiException("bad_response", "The library server sent something unexpected.", cause = e)
    }
}
