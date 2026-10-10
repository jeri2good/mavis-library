package com.mavislibrary.app.data.api

import com.mavislibrary.app.Fixtures
import com.mavislibrary.app.data.model.BookSource
import com.mavislibrary.app.data.model.toBook
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ApiParsingTest {
    @Test
    fun catalogSearchPageParses() {
        val page = ApiParser.catalogPage(Fixtures.read("catalog_search.json"))
        assertNull(page.count)
        assertTrue(page.hasNext)
        assertEquals(3, page.results.size)
        val pp = page.results[0]
        assertEquals(1342, pp.id)
        assertEquals("Austen, Jane", pp.authors.single().name)
        assertNull(pp.copyright)
        assertEquals(true, page.results[2].copyright)
    }

    @Test
    fun gutenbergBookMapsToAppModel() {
        val book = ApiParser.catalogPage(Fixtures.read("catalog_search.json")).results[0].toBook()
        assertEquals("gutenberg:1342", book.key)
        assertEquals(BookSource.GUTENBERG, book.source)
        assertEquals(listOf("EPUB", "Web page"), book.formats.map { it.label })
        assertTrue(book.formats.all { it.free })
        assertEquals("https://www.gutenberg.org/ebooks/1342.epub3.images", book.epubUrl)
    }

    @Test
    fun catalogBookWrapperParses() {
        val json = """{"book":{"id":84,"title":"Frankenstein","authors":[{"name":"Shelley, Mary"}],"copyright":false,"epub":true,"epubUrl":"u","summary":"s"}}"""
        val book = ApiParser.catalogBook(json)
        assertEquals(84, book.id)
        assertEquals(false, book.copyright)
        assertEquals("s", book.summary)
    }

    @Test
    fun openLibraryPageParsesAndMaps() {
        val page = ApiParser.openLibraryPage(Fixtures.read("openlibrary_search.json"))
        assertEquals(2, page.count)
        assertFalse(page.hasNext)
        val first = page.results[0].toBook()
        assertEquals("openlibrary:OL66554W", first.key)
        assertEquals(1813, first.year)
        assertEquals(listOf("Free on Gutenberg", "Public ebook"), first.formats.map { it.label })
        val second = page.results[1].toBook()
        assertNull(second.year)
        assertEquals("Unknown author", second.authorLine)
        assertEquals(listOf("Print or buy"), second.formats.map { it.label })
        assertFalse(second.formats.single().free)
    }

    @Test
    fun malformedBodyBecomesApiException() {
        val e = runCatching { ApiParser.catalogPage("<html>oops</html>") }.exceptionOrNull()
        assertTrue(e is ApiException)
        assertEquals("bad_response", (e as ApiException).code)
    }

    @Test
    fun errorBodyFromSharedFailIsUsed() {
        val e = MavisApi.errorFrom(429, """{"error":"rate_limited","message":"Too many search requests. Wait a minute and try again."}""")
        assertEquals("rate_limited", e.code)
        assertEquals(429, e.status)
        assertEquals("Too many search requests. Wait a minute and try again.", e.message)
        val bare = MavisApi.errorFrom(502, "")
        assertEquals("http_502", bare.code)
        assertTrue(bare.message.contains("server"))
    }

    @Test
    fun endpointParamsMatchHandlers() {
        assertEquals(
            listOf("search" to "austen", "page" to "1", "sort" to "popular"),
            Endpoints.catalogSearch("  austen "),
        )
        assertEquals("2000", Endpoints.catalogSearch("x", page = 99_999).toMap()["page"])
        assertEquals(120, Endpoints.openLibrarySearch("a".repeat(500)).toMap()["q"]!!.length)
        assertEquals("100", Endpoints.openLibrarySearch("x", page = 500).toMap()["page"])
    }
}
