package com.mavislibrary.app.data.db

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.mavislibrary.app.data.model.Book
import com.mavislibrary.app.data.model.BookFormat
import com.mavislibrary.app.data.model.BookSource
import com.mavislibrary.app.data.repo.CachedFetcher
import com.mavislibrary.app.data.repo.RoomResponseCache
import com.mavislibrary.app.data.repo.ShelfRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class ShelfRepositoryTest {
    private val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), MavisDatabase::class.java)
        .allowMainThreadQueries().build()

    @After
    fun close() = db.close()

    private fun book(id: String, authors: List<String> = listOf("Austen, Jane")) = Book(
        key = Book.keyFor(BookSource.GUTENBERG, id), source = BookSource.GUTENBERG, sourceId = id, title = "T$id",
        authors = authors, coverUrl = null, formats = listOf(BookFormat("EPUB", true), BookFormat("In copyright", false)),
        year = 1813, languages = listOf("en"), epubUrl = "e", sourceUrl = "s",
    )

    @Test
    fun addListRemoveAndFieldsSurviveRoundTrip() = runTest {
        var t = 1L
        val repo = ShelfRepository(db.shelf()) { t++ }
        val a = book("1", authors = listOf("Smith, John", "Doe, Jane"))
        repo.add(a)
        repo.add(book("2"))
        repo.add(a)
        assertEquals(setOf("gutenberg:1", "gutenberg:2"), repo.shelfKeys.first())
        val books = repo.shelfBooks.first()
        assertEquals(listOf("gutenberg:2", "gutenberg:1"), books.map { it.key })
        assertEquals(a, books[1])
        repo.remove("gutenberg:1")
        assertEquals(setOf("gutenberg:2"), repo.shelfKeys.first())
    }

    @Test
    fun roomResponseCacheStoresRawJson() = runTest {
        val cache = RoomResponseCache(db.cachedResponses())
        val key = CachedFetcher.cacheKey("/api/catalog", listOf("search" to "x"))
        cache.put(key, """{"a":1}""", 42)
        assertEquals("""{"a":1}""", cache.get(key)!!.body)
        assertEquals(1, db.cachedResponses().pruneOlderThan(100))
    }

    @Test
    fun downloadQueueStubWorks() = runTest {
        val id = db.downloads().enqueue(DownloadQueueEntity(bookKey = "gutenberg:1", url = "u", kind = "epub", createdAt = 1))
        assertEquals(1, db.downloads().withState(DownloadQueueEntity.STATE_QUEUED).size)
        db.downloads().setState(id, DownloadQueueEntity.STATE_DONE)
        assertTrue(db.downloads().withState(DownloadQueueEntity.STATE_QUEUED).isEmpty())
    }
}
