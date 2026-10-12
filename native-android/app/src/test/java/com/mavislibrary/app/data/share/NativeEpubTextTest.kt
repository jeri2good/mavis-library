package com.mavislibrary.app.data.share

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class NativeEpubTextTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun boundedReaderAcceptsLimitAndStopsAfterOneExcessByte() {
        assertArrayEquals(ByteArray(12), NativeEpubText.readBounded(java.io.ByteArrayInputStream(ByteArray(12)), 12))
        val input = java.io.ByteArrayInputStream(ByteArray(100))
        assertTrue(runCatching { NativeEpubText.readBounded(input, 12) }.isFailure)
        assertEquals(87, input.available())
    }

    @Test fun readsSpineOrderNotZipOrder() {
        val epub = temp.newFile("book.epub")
        ZipOutputStream(epub.outputStream()).use { zip ->
            mapOf(
                "mimetype" to EPUB_MIME,
                "META-INF/container.xml" to "<container><rootfile full-path=\"OPS/book.opf\"/></container>",
                "OPS/book.opf" to "<package><manifest><item id=\"a\" href=\"one.xhtml\" media-type=\"application/xhtml+xml\"/><item id=\"b\" href=\"two.xhtml\" media-type=\"application/xhtml+xml\"/></manifest><spine><itemref idref=\"b\"/><itemref idref=\"a\"/></spine></package>",
                "OPS/one.xhtml" to "<html><body><p>First chapter</p></body></html>",
                "OPS/two.xhtml" to "<html><body><h1>Second chapter</h1></body></html>",
            ).forEach { (name, value) -> zip.putNextEntry(ZipEntry(name)); zip.write(value.toByteArray()); zip.closeEntry() }
        }
        assertEquals(listOf("Second chapter", "First chapter"), NativeEpubText.read(epub))
    }

    @Test fun scriptMarkupIsPlainTextNotHtml() {
        val epub = temp.newFile("script.epub")
        ZipOutputStream(epub.outputStream()).use { zip ->
            mapOf(
                "mimetype" to EPUB_MIME,
                "META-INF/container.xml" to "<container><rootfile full-path=\"book.opf\"/></container>",
                "book.opf" to "<package><manifest><item id=\"a\" href=\"chapter.xhtml\" media-type=\"application/xhtml+xml\"/></manifest><spine><itemref idref=\"a\"/></spine></package>",
                "chapter.xhtml" to "<html><body><p>Safe &lt;script&gt;alert('x')&lt;/script&gt;</p><script>alert('bad')</script></body></html>",
            ).forEach { (name, value) -> zip.putNextEntry(ZipEntry(name)); zip.write(value.toByteArray()); zip.closeEntry() }
        }
        val text = NativeEpubText.read(epub).single()
        assertTrue(text.contains("<script>alert('x')</script>"))
        assertFalse(text.contains("alert('bad')"))
    }

    @Test fun fullImportedSectionIncludesAllPagesButNoHeadOrHiddenText() {
        val epub = temp.newFile("full-section.epub")
        val laterPages = "Later page. ".repeat(5000).trim()
        ZipOutputStream(epub.outputStream()).use { zip ->
            mapOf(
                "mimetype" to EPUB_MIME,
                "META-INF/container.xml" to "<container><rootfile full-path=\"book.opf\"/></container>",
                "book.opf" to "<package><manifest><item id=\"a\" href=\"section.xhtml\" media-type=\"application/xhtml+xml\"/></manifest><spine><itemref idref=\"a\"/></spine></package>",
                "section.xhtml" to "<html><head><title>Metadata</title></head><body><h1>Chapter 1</h1><p>Opening page.</p><div hidden=\"hidden\"><p>Invisible</p></div><p>$laterPages</p></body></html>",
            ).forEach { (name, value) -> zip.putNextEntry(ZipEntry(name)); zip.write(value.toByteArray()); zip.closeEntry() }
        }
        val text = NativeEpubText.read(epub).single()
        assertTrue(text.startsWith("Chapter 1\n\nOpening page."))
        assertTrue(text.endsWith(laterPages))
        assertTrue(text.length > 24_000)
        assertFalse(text.contains("Metadata"))
        assertFalse(text.contains("Invisible"))
    }

    @Test fun traversalEntryIsRejectedEvenWhenUnreferenced() {
        val epub = temp.newFile("traversal.epub")
        ZipOutputStream(epub.outputStream()).use { zip ->
            mapOf("mimetype" to EPUB_MIME, "../outside.xhtml" to "<p>Bad</p>").forEach { (name, value) ->
                zip.putNextEntry(ZipEntry(name)); zip.write(value.toByteArray()); zip.closeEntry()
            }
        }
        assertFalse(validateEpub(epub, EPUB_MIME))
        assertTrue(runCatching { NativeEpubText.read(epub) }.isFailure)
    }

    @Test fun oversizedChapterIsRejected() {
        val epub = temp.newFile("large.epub")
        ZipOutputStream(epub.outputStream()).use { zip ->
            mapOf(
                "mimetype" to EPUB_MIME,
                "META-INF/container.xml" to "<container><rootfile full-path=\"book.opf\"/></container>",
                "book.opf" to "<package><manifest><item id=\"a\" href=\"chapter.xhtml\" media-type=\"application/xhtml+xml\"/></manifest><spine><itemref idref=\"a\"/></spine></package>",
                "chapter.xhtml" to "<p>${"a".repeat(2 * 1024 * 1024)}</p>",
            ).forEach { (name, value) -> zip.putNextEntry(ZipEntry(name)); zip.write(value.toByteArray()); zip.closeEntry() }
        }
        assertTrue(validateEpub(epub, EPUB_MIME))
        assertTrue(runCatching { NativeEpubText.read(epub) }.isFailure)
    }

    @Test fun excessiveZipEntriesRejected() {
        val epub = temp.newFile("many.epub")
        ZipOutputStream(epub.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("mimetype")); zip.write(EPUB_MIME.toByteArray()); zip.closeEntry()
            repeat(10_000) { index -> zip.putNextEntry(ZipEntry("entry$index")); zip.closeEntry() }
        }
        assertFalse(validateEpub(epub, EPUB_MIME))
    }

    @Test fun routeRejectsTraversalUnknownAndKids() {
        val id = UUID.randomUUID().toString()
        assertTrue(importedRouteAllowed(id, listOf(id), false))
        assertFalse(importedRouteAllowed("../$id", listOf(id), false))
        assertFalse(importedRouteAllowed("$id/../foo", listOf(id), false))
        assertFalse(importedRouteAllowed(UUID.randomUUID().toString(), listOf(id), false))
        assertFalse(importedRouteAllowed(id, listOf(id), true))
    }

    @Test fun rejectsExternalEntities() {
        val epub = temp.newFile("unsafe.epub")
        ZipOutputStream(epub.outputStream()).use { zip ->
            mapOf("META-INF/container.xml" to "<!DOCTYPE container [<!ENTITY x SYSTEM 'file:///etc/passwd'>]><container>&x;</container>").forEach { (name, value) ->
                zip.putNextEntry(ZipEntry(name)); zip.write(value.toByteArray()); zip.closeEntry()
            }
        }
        assertTrue(runCatching { NativeEpubText.read(epub) }.isFailure)
    }
}
