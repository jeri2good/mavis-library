package com.mavislibrary.app.data.share

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.test.core.app.ApplicationProvider
import com.mavislibrary.app.data.kids.kidsPrefs
import com.mavislibrary.app.ui.theme.Look
import com.mavislibrary.app.ui.theme.colorSchemeFor
import androidx.compose.ui.graphics.toArgb
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.flow.first
import org.robolectric.Shadows

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ShareAndroidTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    @Test fun cardsRenderAllLooksWithThemeBackground() {
        Look.entries.forEach { look ->
            val card = renderVerseCard("John 3:16", "For God so loved the world", look)
            assertEquals(1080, card.width); assertEquals(1080, card.height)
            assertEquals(colorSchemeFor(look).background.toArgb(), card.getPixel(0, 0))
            card.recycle()
        }
    }
    @Test fun oversizedVerseRefusedInsteadOfClipped() {
        assertTrue(runCatching { renderVerseCard("John 3", "long passage ".repeat(400), Look.PAPER) }.isFailure)
    }
    @Test fun providerOnlyExposesShareDirectory() {
        val allowed = File(context.cacheDir, "share/test.png").apply { parentFile!!.mkdirs(); writeBytes(byteArrayOf(1)) }
        assertEquals("content", FileProvider.getUriForFile(context, "${context.packageName}.share", allowed).scheme)
        val privateFile = File(context.filesDir, "private.epub").apply { writeBytes(byteArrayOf(1)) }
        assertTrue(runCatching { FileProvider.getUriForFile(context, "${context.packageName}.share", privateFile) }.isFailure)
    }
    @Test fun kidsModeRejectsImportBeforeTouchingProvider() = runBlocking {
        val prefs = context.kidsPrefs()
        prefs.start("Sam", 15, "1234")
        try {
            assertNull(EpubImports(context).import(Uri.parse("content://missing/file.epub"), EPUB_MIME))
        } finally { prefs.leave("1234") }
    }
    @Test fun localFileAndNetworkUrisRejected() = runBlocking {
        listOf("file:///etc/passwd", "https://example.com/book.epub").forEach { uri ->
            assertTrue(runCatching { EpubImports(context).import(Uri.parse(uri), EPUB_MIME) }.exceptionOrNull() is ImportRejected)
        }
    }
    @Test fun validContentImportPersistsInsideFilesDir() = runBlocking {
        val bytes = ByteArrayOutputStream().also { output ->
            ZipOutputStream(output).use { zip ->
                zip.putNextEntry(ZipEntry("mimetype")); zip.write(EPUB_MIME.toByteArray()); zip.closeEntry()
            }
        }.toByteArray()
        val uri = Uri.parse("content://test.book/valid.epub")
        Shadows.shadowOf(context.contentResolver).registerInputStream(uri, ByteArrayInputStream(bytes))
        val imports = EpubImports(context)
        val book = imports.import(uri, EPUB_MIME)!!
        assertTrue(EpubImports(context).books.first().contains(book))
        val file = File(context.filesDir, "imports/${book.id}.epub")
        assertTrue(file.canonicalPath.startsWith(context.filesDir.canonicalPath + "/"))
        assertArrayEquals(bytes, file.readBytes())
    }
    @Test fun invalidContentLeavesNoPartialFileOrIndexEntry() = runBlocking {
        val imports = EpubImports(context)
        val before = imports.books.first()
        val dir = File(context.filesDir, "imports")
        val beforeFiles = dir.list().orEmpty().toSet()
        val uri = Uri.parse("content://test.book/invalid.epub")
        Shadows.shadowOf(context.contentResolver).registerInputStream(uri, ByteArrayInputStream(byteArrayOf(1,2,3)))
        assertTrue(runCatching { imports.import(uri, EPUB_MIME) }.exceptionOrNull() is ImportRejected)
        assertEquals(before, imports.books.first())
        assertEquals(beforeFiles, dir.list().orEmpty().toSet())
    }
    @Test fun onlySupportedActionsSupplyEpubUris() {
        val uri = Uri.parse("content://example/book.epub")
        assertEquals(uri, sharedEpubUri(Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uri)))
        assertEquals(uri, sharedEpubUri(Intent(Intent.ACTION_VIEW, uri)))
        assertNull(sharedEpubUri(Intent(Intent.ACTION_EDIT, uri)))
    }
}
