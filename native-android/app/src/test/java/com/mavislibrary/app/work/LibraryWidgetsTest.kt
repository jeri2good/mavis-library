package com.mavislibrary.app.work

import com.mavislibrary.app.data.db.BookEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryWidgetsTest {
    @Test fun pickerCoversEveryDayDeterministically() {
        assertTrue(VerseOfDay.size in 55..65)
        (1..366).forEach { day ->
            val verse = VerseOfDay.forDay(day)
            assertEquals(verse, VerseOfDay.forDay(day))
            assertTrue(verse.text.isNotBlank())
            assertTrue(verse.route.startsWith("#/bible/"))
        }
        assertEquals("#/bible/Gen/1?v=1&t=kjv", VerseOfDay.forDay(1).route)
        assertEquals("#/bible/Ps/23?v=1&t=kjv", VerseOfDay.forDay(16).route)
        assertEquals(VerseOfDay.forDay(1), VerseOfDay.forDay(VerseOfDay.size + 1))
    }

    @Test fun lastOpenedAdultBookOnly() {
        val adult = book("gutenberg:1342", "1342", "Pride and Prejudice")
        val child = book("gutenberg:14838", "14838", "Child story")
        assertEquals(adult, continueReading(listOf(child, adult), adult.key))
        assertNull(continueReading(listOf(adult, child), child.key))
        assertNull(continueReading(listOf(adult), "missing"))
        assertNull(continueReading(listOf(adult), null))
    }

    private fun book(key: String, id: String, title: String) = BookEntity(
        key, "GUTENBERG", id, title, "", null, "", null, "", null, null, 0L,
    )
}
