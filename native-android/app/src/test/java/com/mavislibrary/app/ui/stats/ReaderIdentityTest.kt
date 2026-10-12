package com.mavislibrary.app.ui.stats

import org.junit.Assert.*
import org.junit.Test

class ReaderIdentityTest {
    @Test fun countsReaderAndBibleButNotBookDetailsOrForeignPages() {
        assertEquals("book:gutenberg:1342", readerIdentity("https://localhost/#/read/gutenberg%3A1342"))
        assertEquals("bible:John", readerIdentity("https://localhost/#/bible/John/3?v=16"))
        assertNull(readerIdentity("https://localhost/#/book/gutenberg%3A1342"))
        assertNull(readerIdentity("https://localhost.evil/#/read/gutenberg:1342"))
        assertNull(readerIdentity(null))
    }
}
