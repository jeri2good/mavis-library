package com.mavislibrary.app.ui.scan

import com.mavislibrary.app.data.model.Book
import com.mavislibrary.app.data.model.BookSource
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class ScanLogicTest {
    @Test fun isbnChecksumsAndNormalization() {
        assertEquals("9780306406157", IsbnValidator.normalize("0-306-40615-2"))
        assertEquals("9780804429573", IsbnValidator.normalize("0-8044-2957-X"))
        assertEquals("9780306406157", IsbnValidator.normalize("978-0-306-40615-7"))
        assertEquals("9791090636071", IsbnValidator.normalize("9791090636071"))
        listOf("0306406153", "9780306406158", "9791090636072", "123456789012", "012345678905", "0000000000000", "9780306406157junk", "978 030640615X").forEach {
            assertNull(it, IsbnValidator.normalize(it))
        }
    }

    @Test fun consecutiveFramesAndRepeatCooldown() {
        var now = 100L
        val scan = ScanDebouncer { now }
        assertNull(scan.offer("9780306406158"))
        assertNull(scan.offer("9780306406157"))
        assertEquals("9780306406157", scan.offer("9780306406157"))
        now += 100
        assertNull(scan.offer("9780306406157"))
        assertNull(scan.offer(null))
        now += 3001
        assertNull(scan.offer("9780306406157"))
        assertEquals("9780306406157", scan.offer("9780306406157"))
    }

    @Test fun changedCandidateMustStabilize() {
        val scan = ScanDebouncer { 400L }
        assertNull(scan.offer("9780306406157"))
        assertNull(scan.offer("9780804429573"))
        assertEquals("9780804429573", scan.offer("9780804429573"))
    }

    @Test fun fakeResolverCoversFoundMissingOfflineAndError() = runTest {
        val book = Book("openlibrary:OL1W", BookSource.OPEN_LIBRARY, "OL1W", "Found", listOf("Author"), null, emptyList(), null, emptyList(), null, null)
        for (expected in listOf(ScanLookup.Found(book), ScanLookup.NotFound, ScanLookup.Offline, ScanLookup.Error)) {
            val fake = ScanResultResolver { expected }
            assertEquals(expected, fake.resolve("9780306406157"))
        }
    }

    @Test fun scanRouteUsesAdultGate() {
        assertFalse(scanRouteAllowed(true, true))
        assertFalse(scanRouteAllowed(false, false))
        assertTrue(scanRouteAllowed(false, true))
    }
}
