package com.mavislibrary.app.data.share

import org.junit.Assert.*
import org.junit.Test
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ShareCoreTest {
    @get:Rule val tmp = TemporaryFolder()
    private fun epub(mime: String = EPUB_MIME, content: ByteArray = byteArrayOf(1)) = tmp.newFile().also { file ->
        ZipOutputStream(file.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("mimetype")); zip.write(mime.toByteArray()); zip.closeEntry()
            zip.putNextEntry(ZipEntry("book.xhtml")); zip.write(content); zip.closeEntry()
        }
    }
    @Test fun validEpub() { assertTrue(validateEpub(epub(), EPUB_MIME)) }
    @Test fun wrongDeclaredMime() { assertFalse(validateEpub(epub(), "application/zip")) }
    @Test fun wrongMimeEntry() { assertFalse(validateEpub(epub("text/plain"), EPUB_MIME)) }
    @Test fun truncatedZip() { val f = epub(); f.writeBytes(f.readBytes().take(20).toByteArray()); assertFalse(validateEpub(f, EPUB_MIME)) }
    @Test fun missingMimeEntry() {
        val f = tmp.newFile(); ZipOutputStream(f.outputStream()).use { it.putNextEntry(ZipEntry("a")); it.write(1) }
        assertFalse(validateEpub(f, EPUB_MIME))
    }
    @Test fun oversizedCompressedPayloadRejectedWithoutExtraction() {
        val f = tmp.newFile()
        ZipOutputStream(f.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("mimetype")); zip.write(EPUB_MIME.toByteArray()); zip.closeEntry()
            zip.putNextEntry(ZipEntry("large")); val chunk = ByteArray(1024 * 1024)
            repeat(61) { zip.write(chunk) }; zip.closeEntry()
        }
        assertTrue(f.length() < EPUB_LIMIT)
        assertFalse(validateEpub(f, EPUB_MIME))
    }
    @Test fun boundedCopyDoesNotTrustProviderSize() {
        val f = tmp.newFile()
        assertTrue(runCatching { copyEpub(ByteArrayInputStream(ByteArray(101)), f, 100) }.exceptionOrNull() is ImportRejected)
        assertTrue(f.length() <= 100)
    }
    @Test fun bibleReferencesUseExistingRoutes() {
        assertEquals("#/bible/John/3?v=16", parseBibleReference("John 3:16")?.route)
        assertEquals("#/bible/1Cor/13", parseBibleReference("1 Cor 13")?.route)
        assertEquals("#/bible/Gen/1?v=1", parseBibleReference("Genesis 1:1-3")?.route)
        assertEquals("#/bible/2Tim/2?v=1", parseBibleReference(" 2 Timothy 2:1 ")?.route)
    }
    @Test fun junkAndInvalidRangesAreRejected() {
        listOf("", "hello", "Buy John 3:16 now", "John 0:1", "John 22", "John 3:0", "Genesis 1:3-1", "John 3:16<script>", "https://example.com/John3:16").forEach { assertNull(it, parseBibleReference(it)) }
    }
    @Test fun wrapsAtWordsAndHardBreaksLongTokens() {
        assertEquals(listOf("one two", "three"), wrapVerse("one two three", 7f) { it.length.toFloat() })
        assertEquals(listOf("abc", "def", "g"), wrapVerse("abcdefg", 3f) { it.length.toFloat() })
        assertEquals(listOf("a", "", "b"), wrapVerse("a\n\nb", 3f) { it.length.toFloat() })
    }
}
