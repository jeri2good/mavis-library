package com.mavislibrary.app.data.repo

import com.mavislibrary.app.data.api.ApiParser
import com.mavislibrary.app.data.api.Endpoints
import com.mavislibrary.app.data.db.ShelfDao
import com.mavislibrary.app.data.db.ShelfEntryEntity
import com.mavislibrary.app.data.db.toBook
import com.mavislibrary.app.data.db.toEntity
import com.mavislibrary.app.data.model.Book
import com.mavislibrary.app.data.model.BookSource
import com.mavislibrary.app.data.model.toBook
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

data class SearchPage(val books: List<Book>, val page: Int, val hasNext: Boolean)

class SearchRepository(private val fetcher: CachedFetcher) {
    suspend fun search(source: BookSource, query: String, page: Int = 1): Loaded<SearchPage> = when (source) {
        BookSource.GUTENBERG -> fetcher.fetch(Endpoints.CATALOG, Endpoints.catalogSearch(query, page)) { body ->
            val p = ApiParser.catalogPage(body)
            // catalog.mjs already drops confirmed-copyright books for ?ids=; do the same for searches.
            SearchPage(p.results.filter { it.copyright != true }.map { it.toBook() }, p.page, p.hasNext)
        }
        BookSource.OPEN_LIBRARY -> fetcher.fetch(Endpoints.OPEN_LIBRARY, Endpoints.openLibrarySearch(query, page)) { body ->
            val p = ApiParser.openLibraryPage(body)
            SearchPage(p.results.map { it.toBook() }, p.page, p.hasNext)
        }
    }
}

class ShelfRepository(private val dao: ShelfDao, private val clock: () -> Long = System::currentTimeMillis) {
    val shelfKeys: Flow<Set<String>> = dao.keys().map { it.toSet() }
    val shelfBooks: Flow<List<Book>> = dao.books().map { list -> list.map { it.toBook() } }

    suspend fun add(book: Book) {
        val now = clock()
        dao.addToShelf(book.toEntity(now), ShelfEntryEntity(bookKey = book.key, addedAt = now))
    }

    suspend fun remove(key: String) = dao.remove(key)
}
