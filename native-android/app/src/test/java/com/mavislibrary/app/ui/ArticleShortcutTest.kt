package com.mavislibrary.app.ui

import android.content.Context
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import com.mavislibrary.app.data.kids.kidsPrefs
import com.mavislibrary.app.data.podcast.PodcastDraft
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ArticleShortcutTest {
    @get:Rule val compose = createComposeRule()

    @Test fun openedArticleButtonPrefillsWithoutCreatingAnything() {
        val source = PodcastDraft.article("Test article", "https://example.org/article")
        var selected: PodcastDraft? = null
        compose.setContent { ArticleScreen(source, {}, { selected = it }) }
        compose.waitUntil(5_000) { compose.onAllNodes(hasTestTag("article_podcast")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("article_podcast").performClick()
        assertEquals(source, selected)
        assertEquals("article", selected!!.request("debate", "short", "local").kind)
    }

    @Test fun importedReaderButtonPrefillsCurrentChapterAfterNext() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val output = java.io.ByteArrayOutputStream()
        java.util.zip.ZipOutputStream(output).use { zip ->
            mapOf(
                "mimetype" to com.mavislibrary.app.data.share.EPUB_MIME,
                "META-INF/container.xml" to "<container><rootfile full-path=\"book.opf\"/></container>",
                "book.opf" to "<package><manifest><item id=\"a\" href=\"one.xhtml\" media-type=\"application/xhtml+xml\"/><item id=\"b\" href=\"two.xhtml\" media-type=\"application/xhtml+xml\"/></manifest><spine><itemref idref=\"a\"/><itemref idref=\"b\"/></spine></package>",
                "one.xhtml" to "<html><body><p>Do not send chapter one</p></body></html>",
                "two.xhtml" to "<html><body><p>Send all of chapter two</p><p>${"Later page. ".repeat(5000)}</p></body></html>",
            ).forEach { (name, text) -> zip.putNextEntry(java.util.zip.ZipEntry(name)); zip.write(text.toByteArray()); zip.closeEntry() }
        }
        val uri = android.net.Uri.parse("content://test.book/chapters.epub")
        org.robolectric.Shadows.shadowOf(context.contentResolver).registerInputStream(uri, output.toByteArray().inputStream())
        val imports = com.mavislibrary.app.data.share.EpubImports(context)
        val book = runBlocking { imports.import(uri, com.mavislibrary.app.data.share.EPUB_MIME)!! }
        var selected: PodcastDraft? = null
        compose.setContent { ImportedReader(book.id, "Test Book", imports, {}, { selected = it }) }
        compose.waitUntil(5_000) { compose.onAllNodes(hasText("Next")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Next").performClick()
        compose.onNodeWithText("Make podcast of this chapter").performClick()
        assertEquals("Test Book - Chapter 2", selected!!.title)
        assertEquals("chapter", selected!!.kind)
        assertTrue(selected!!.text.startsWith("Send all of chapter two"))
        assertTrue(selected!!.text.length > 24_000)
        assertFalse(selected!!.text.contains("Do not send chapter one"))
        assertNull(selected!!.gutenbergId)
    }

    @Test fun kidsModeDoesNotComposeArticleOrPodcastButton() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = context.kidsPrefs()
        runBlocking { prefs.start("Sam", 15, "1234") }
        try {
            compose.setContent { ArticleScreen(PodcastDraft.article("Hidden", "https://example.org/article"), {}, {}) }
            compose.waitForIdle()
            compose.onNodeWithTag("article_reader").assertDoesNotExist()
            compose.onNodeWithTag("article_podcast").assertDoesNotExist()
        } finally { runBlocking { prefs.leave("1234") } }
    }
}
