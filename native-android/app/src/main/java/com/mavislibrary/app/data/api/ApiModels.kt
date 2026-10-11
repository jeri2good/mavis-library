package com.mavislibrary.app.data.api

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/*
 * Shapes returned by the Netlify functions (read from netlify/functions/catalog.mjs,
 * netlify/lib/gutenberg.mjs listBook/parseBook and netlify/functions/openlibrary.mjs shapeDoc).
 * Unknown fields are ignored so the server can add fields without breaking the app.
 */

@Serializable
data class GutenbergAuthor(
    val name: String = "",
    val birthYear: Int? = null,
    val deathYear: Int? = null,
)

/** One book from GET /api/catalog (list entries and the full ?id= record share this shape). */
@Serializable
data class GutenbergBook(
    val id: Int,
    val title: String = "",
    val authors: List<GutenbergAuthor> = emptyList(),
    val translators: List<GutenbergAuthor> = emptyList(),
    val subjects: List<String> = emptyList(),
    val bookshelves: List<String> = emptyList(),
    val languages: List<String> = emptyList(),
    /** null = not yet confirmed (list feeds), false = public domain in the USA, true = still in copyright. */
    val copyright: Boolean? = null,
    val mediaType: String? = null,
    val downloads: Int? = null,
    val cover: String? = null,
    val epub: Boolean = false,
    val epubUrl: String? = null,
    val htmlUrl: String? = null,
    val sourceUrl: String? = null,
    val summary: String? = null,
    val rights: String? = null,
)

/** GET /api/catalog?search=&topic=&languages=&page=&sort=  and  ?ids= */
@Serializable
data class CatalogPage(
    /** Gutenberg's feed does not report a total, so this is null for searches. */
    val count: Int? = null,
    val page: Int = 1,
    val pageSize: Int = 25,
    val hasNext: Boolean = false,
    val hasPrev: Boolean = false,
    val results: List<GutenbergBook> = emptyList(),
)

/** GET /api/catalog?id= */
@Serializable
data class CatalogBookResponse(val book: GutenbergBook)

/** One search result from GET /api/openlibrary?q=  (discovery only; never implies the book is free). */
@Serializable
data class OpenLibraryDoc(
    val id: String,
    val title: String = "",
    val authors: List<String> = emptyList(),
    val firstPublishYear: Int? = null,
    val cover: String? = null,
    val editionCount: Int? = null,
    val languages: List<String> = emptyList(),
    val isbn: String? = null,
    val subjects: List<String> = emptyList(),
    /** Open Library's ebook_access: "public", "borrowable", "printdisabled", "no_ebook" or null. */
    val ebookAccess: String? = null,
    val gutenbergIds: List<String> = emptyList(),
    val sourceUrl: String? = null,
)

@Serializable
data class OpenLibraryPage(
    val count: Int = 0,
    val page: Int = 1,
    val pageSize: Int = 20,
    val hasNext: Boolean = false,
    val hasPrev: Boolean = false,
    val results: List<OpenLibraryDoc> = emptyList(),
)

/** Every function fails with { error, message } (netlify/lib/shared.mjs fail()). */
@Serializable
data class ApiErrorBody(val error: String = "error", val message: String = "")

@Serializable
data class LibriVoxSection(
    val n: Int,
    val title: String = "",
    val url: String,
    val secs: Long = 0,
    val reader: String = "",
)

@Serializable
data class LibriVoxVersion(
    val id: String,
    val title: String = "",
    val language: String = "",
    val readers: List<String> = emptyList(),
    val moreReaders: Int = 0,
    val totalSecs: Long = 0,
    val url: String? = null,
    val sections: List<LibriVoxSection> = emptyList(),
)

@Serializable
data class LibriVoxResponse(val versions: List<LibriVoxVersion> = emptyList())

val ApiJson = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    coerceInputValues = true
}


/** One curated shelf from GET /api/shelves (ids only; the books are loaded from /api/catalog?ids=). */
@Serializable
data class ShelfDef(
    val id: String,
    val label: String,
    val blurb: String = "",
    val ids: List<Int> = emptyList(),
    val kids: Boolean = false,
)

@Serializable
data class ShelvesResponse(val shelves: List<ShelfDef> = emptyList())


/** One story or paper from GET /api/tech. */
@Serializable
data class TechItem(
    val id: String,
    val title: String,
    val url: String,
    val points: Int = 0,
    val comments: Int = 0,
    val source: String = "",
    val summary: String = "",
)

@Serializable
data class TechResponse(val kind: String = "", val items: List<TechItem> = emptyList())
