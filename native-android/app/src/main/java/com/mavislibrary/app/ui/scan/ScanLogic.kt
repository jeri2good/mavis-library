package com.mavislibrary.app.ui.scan

import com.mavislibrary.app.data.api.ApiException
import com.mavislibrary.app.data.kids.adultRoutesAllowed
import com.mavislibrary.app.data.model.Book
import com.mavislibrary.app.data.model.BookSource
import com.mavislibrary.app.data.repo.SearchRepository
import kotlinx.coroutines.CancellationException

fun scanRouteAllowed(kidsMode: Boolean, hasCamera: Boolean) = adultRoutesAllowed(kidsMode) && hasCamera

object IsbnValidator {
    fun normalize(input: String): String? {
        val isbn = input.replace("-", "").replace(" ", "").uppercase()
        if (isbn.length == 10 && isbn.take(9).all(Char::isDigit) && (isbn.last().isDigit() || isbn.last() == 'X')) {
            val sum = isbn.mapIndexed { i, c -> (10 - i) * if (c == 'X') 10 else c.digitToInt() }.sum()
            if (sum % 11 != 0) return null
            return with13("978" + isbn.take(9))
        }
        if (isbn.length != 13 || !isbn.all(Char::isDigit) || !isbn.startsWith("978") && !isbn.startsWith("979")) return null
        return isbn.takeIf { with13(it.take(12)) == it }
    }

    private fun with13(first12: String): String {
        val sum = first12.mapIndexed { i, c -> c.digitToInt() * if (i % 2 == 0) 1 else 3 }.sum()
        return first12 + ((10 - sum % 10) % 10)
    }
}

class ScanDebouncer(private val clock: () -> Long = System::currentTimeMillis) {
    private var candidate: String? = null
    private var firstAt = 0L
    private var frames = 0
    private var lastAccepted: String? = null
    private var acceptedAt = 0L

    @Synchronized fun offer(raw: String?): String? {
        val now = clock()
        val isbn = raw?.let(IsbnValidator::normalize)
        if (isbn == null) { candidate = null; frames = 0; return null }
        if (isbn != candidate) { candidate = isbn; firstAt = now; frames = 1; return null }
        frames++
        if (frames < 2 && now - firstAt < 400) return null
        if (lastAccepted == isbn && now - acceptedAt < 3_000) return null
        lastAccepted = isbn; acceptedAt = now
        return isbn
    }
}

sealed interface ScanLookup {
    data class Found(val book: Book) : ScanLookup
    data object NotFound : ScanLookup
    data object Offline : ScanLookup
    data object Error : ScanLookup
}

fun interface ScanResultResolver {
    suspend fun resolve(isbn: String): ScanLookup
}

class OpenLibraryScanResolver(private val search: SearchRepository) : ScanResultResolver {
    override suspend fun resolve(isbn: String): ScanLookup = try {
        val result = search.search(BookSource.OPEN_LIBRARY, "isbn:$isbn").value.books.firstOrNull()
        if (result == null) ScanLookup.NotFound else ScanLookup.Found(result)
    } catch (e: CancellationException) {
        throw e
    } catch (e: ApiException) {
        if (e.offline) ScanLookup.Offline else ScanLookup.Error
    } catch (_: Exception) {
        ScanLookup.Error
    }
}
