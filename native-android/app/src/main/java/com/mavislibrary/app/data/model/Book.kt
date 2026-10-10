package com.mavislibrary.app.data.model

import com.mavislibrary.app.data.api.GutenbergBook
import com.mavislibrary.app.data.api.OpenLibraryDoc

enum class BookSource(val label: String) {
    GUTENBERG("Free classics"),
    OPEN_LIBRARY("All books"),
}

/** What a format chip says and whether the app can read it for free. */
data class BookFormat(val label: String, val free: Boolean)

/** App-wide book model used by Search, the shelf and the database. */
data class Book(
    /** Stable key shared with the web app: "gutenberg:1342" or "openlibrary:OL893414W". */
    val key: String,
    val source: BookSource,
    val sourceId: String,
    val title: String,
    val authors: List<String>,
    val coverUrl: String?,
    val formats: List<BookFormat>,
    val year: Int?,
    val languages: List<String>,
    val epubUrl: String?,
    val sourceUrl: String?,
) {
    val authorLine: String get() = authors.joinToString(", ").ifBlank { "Unknown author" }

    companion object {
        fun keyFor(source: BookSource, id: String) = when (source) {
            BookSource.GUTENBERG -> "gutenberg:$id"
            BookSource.OPEN_LIBRARY -> "openlibrary:$id"
        }
    }
}

fun GutenbergBook.toBook(): Book = Book(
    key = Book.keyFor(BookSource.GUTENBERG, id.toString()),
    source = BookSource.GUTENBERG,
    sourceId = id.toString(),
    title = title.ifBlank { "Untitled" },
    authors = authors.map { it.name }.filter { it.isNotBlank() },
    coverUrl = cover,
    formats = buildList {
        if (epub && epubUrl != null) add(BookFormat("EPUB", free = true))
        if (htmlUrl != null) add(BookFormat("Web page", free = true))
        if (copyright == true) add(BookFormat("In copyright", free = false))
    },
    year = null,
    languages = languages,
    epubUrl = epubUrl,
    sourceUrl = sourceUrl,
)

fun OpenLibraryDoc.toBook(): Book = Book(
    key = Book.keyFor(BookSource.OPEN_LIBRARY, id),
    source = BookSource.OPEN_LIBRARY,
    sourceId = id,
    title = title.ifBlank { "Untitled" },
    authors = authors.filter { it.isNotBlank() },
    coverUrl = cover,
    formats = buildList {
        if (gutenbergIds.isNotEmpty()) add(BookFormat("Free on Gutenberg", free = true))
        when (ebookAccess) {
            "public" -> add(BookFormat("Public ebook", free = true))
            "borrowable" -> add(BookFormat("Borrow (Open Library)", free = false))
            "printdisabled" -> add(BookFormat("Print-disabled access", free = false))
        }
        if (isEmpty()) add(BookFormat("Print or buy", free = false))
    },
    year = firstPublishYear,
    languages = languages,
    epubUrl = null,
    sourceUrl = sourceUrl,
)
