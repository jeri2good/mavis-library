package com.mavislibrary.app.audio

import com.mavislibrary.app.data.db.AudioResumeDao
import com.mavislibrary.app.data.db.AudioResumeEntity
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ResumePositionStoreTest {
    @Test fun `save clamps unsafe values and latest follows dao`() = runTest {
        val dao = MemoryResumeDao()
        val store = ResumePositionStore(dao) { 1234L }
        assertNull(store.load("missing"))
        store.save(ResumePosition("book", -2, -50, 4f))
        assertEquals(ResumePosition("book", 0, 0, 2f), store.load("book"))
        assertEquals(1234L, dao.row?.updatedAt)
        assertEquals(store.load("book"), store.latest())
    }

    private class MemoryResumeDao : AudioResumeDao {
        var row: AudioResumeEntity? = null
        override suspend fun put(position: AudioResumeEntity) { row = position }
        override suspend fun get(bookKey: String): AudioResumeEntity? = row?.takeIf { it.bookKey == bookKey }
        override suspend fun latest(): AudioResumeEntity? = row
    }
}
