package com.mavislibrary.app.data.kids

import org.junit.Assert.*
import org.junit.Test

class RoundTwoCatalogTest {
    private fun resource(name: String) = javaClass.getResource("/kids/$name")!!.readText()
    @Test fun publishedCatalogAcceptsDecimalsUnknownFieldsAndTierDefaults() {
        val catalog = StoryPacks.catalog(resource("real-catalog-v1.json"))
        assertTrue(catalog.stories.isNotEmpty())
        assertTrue(catalog.stories.any { it.minutes.values.any { n -> n != n.toInt().toDouble() } })
        for (age in 2..7) assertFalse(catalog.voicesFor(age).any { it.id == "storyteller" })
        for (age in 8..10) assertEquals("storyteller", catalog.narrator(age, null))
        assertEquals("host", catalog.narrator(8, "host"))
        assertEquals("dad", catalog.narrator(10, "dad"))
        assertEquals("host", catalog.narrator(5, "storyteller"))
        assertEquals("storyteller", catalog.copy(tiers = emptyList()).narrator(8, null))
        assertEquals("host", catalog.copy(voices = catalog.voices.filter { it.id != "storyteller" }).narrator(8, null))
        assertEquals("British accent", catalog.voices.single { it.id == "grandma" }.label)
        assertTrue(catalog.credits.contains("Brad Harrington"))
    }
    @Test fun missingChosenClipUsesHostForThatPageOnly() {
        val page = StoryPage("img/01.jpg", audio = mapOf("host" to mapOf("t3" to "host.mp3"),
            "storyteller" to mapOf("t3" to "recorded.mp3")))
        assertEquals("recorded.mp3", page.clip("storyteller", "t3"))
        assertEquals("host.mp3", page.copy(audio = page.audio - "storyteller").clip("storyteller", "t3"))
        assertEquals("host.mp3", page.clip("dad", "t3"))
        assertNull(page.clip("host", "t1"))
    }
    @Test fun publishedLongListenParsesAndConvertsAllChapters() {
        val catalog = LongListen.parse(resource("real-longlisten-v1.json"))
        assertEquals(8, catalog.books.size)
        assertTrue(catalog.credits.contains("public domain"))
        catalog.books.forEach {
            assertTrue(it.sections.isNotEmpty())
            assertEquals(it.sections.size, it.version().sections.size)
            assertTrue(it.version().id.startsWith("kids-librivox-"))
            assertEquals(it.sections.first().mp3, it.version().sections.first().url)
        }
    }
    @Test fun longListenRejectsExternalAndLocalMedia() {
        val source = resource("real-longlisten-v1.json")
        listOf("http://archive.org/", "https://evil.test/", "file:///").forEach { prefix ->
            assertThrows(IllegalArgumentException::class.java) {
                LongListen.parse(source.replace("https://archive.org/", prefix))
            }
        }
    }
}
