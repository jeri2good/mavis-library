package com.mavislibrary.app.data.share

import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26])
class NativeEpubMinSdkTest {
    @get:Rule val tmp = TemporaryFolder()
    @Test fun extractsImportedPodcastSourceOnAndroidEight() {
        val epub = tmp.newFile("minimum-sdk.epub")
        ZipOutputStream(epub.outputStream()).use { zip ->
            mapOf(
                "mimetype" to EPUB_MIME,
                "META-INF/container.xml" to "<container><rootfile full-path=\"book.opf\"/></container>",
                "book.opf" to "<package><manifest><item id=\"chapter\" href=\"chapter.xhtml\" media-type=\"application/xhtml+xml\"/></manifest><spine><itemref idref=\"chapter\"/></spine></package>",
                "chapter.xhtml" to "<html><body><p>Offline chapter text for the studio.</p></body></html>",
            ).forEach { (name, value) -> zip.putNextEntry(ZipEntry(name)); zip.write(value.toByteArray()); zip.closeEntry() }
        }
        assertEquals(listOf("Offline chapter text for the studio."), NativeEpubText.read(epub))
    }
}
