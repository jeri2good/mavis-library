package com.mavislibrary.app.data.reader

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.mavislibrary.app.data.api.TechItem
import com.mavislibrary.app.data.feeds.FeedSource
import com.mavislibrary.app.data.feeds.FeedStory
import com.mavislibrary.app.ui.sites.SitesUiState
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ReadStateTest {
    @get:Rule val temp = TemporaryFolder()
    private fun story(id: String, at: Long) = FeedStory(TechItem(id, "Story $id", "https://example.com/$id"), at)

    @Test fun capKeepsNewest500PerSiteAndMarkAllPersists() = runTest {
        val data = PreferenceDataStoreFactory.create(scope = backgroundScope) { temp.root.resolve("read.preferences_pb") }
        val store = ReadStateStore(data)
        (0 until 520).forEach { store.markRead("a", listOf("id$it")) }
        store.markRead("b", listOf("x"))
        val a = store.read.first().getValue("a")
        assertEquals(ReadStateStore.CAP, a.size)
        assertFalse("oldest ids are dropped first", "id0" in a || "id19" in a)
        assertTrue("id20" in a && "id519" in a)
        store.markRead("a", listOf("id20"))
        store.markRead("a", listOf("new"))
        assertTrue("re-reading refreshes an id so it survives the cap", "id20" in ReadStateStore(data).read.first().getValue("a"))
        store.markRead("b", listOf("y", "z", "y"))
        assertEquals(setOf("x", "y", "z"), ReadStateStore(data).read.first().getValue("b"))
        store.markRead("b", emptyList())
        assertEquals(setOf("x", "y", "z"), store.read.first().getValue("b"))
    }

    @Test fun unreadCountsPerSiteIgnoreReadIds() {
        val one = FeedSource("one", "One", "https://one.example/feed", "https://one.example/", 0)
        val two = FeedSource("two", "Two", "https://two.example/feed", "https://two.example/", 0)
        val state = SitesUiState(
            sources = listOf(one, two),
            posts = mapOf("one" to listOf(story("a", 3), story("b", 2), story("c", 1)), "two" to listOf(story("d", 5))),
            read = mapOf("one" to setOf("b", "stale")),
        )
        assertEquals(2, state.unreadCount(one))
        assertEquals(1, state.unreadCount(two))
        assertTrue(state.isRead("one", story("b", 2)))
        assertEquals(listOf("a", "c"), state.posts.getValue("one").unread(state.read.getValue("one")).map { it.item.id })
        assertEquals("All new merges sites newest first", listOf("d", "a", "c"), state.allUnread().map { it.second.item.id })
        assertEquals(listOf("a", "c"), state.copy(sources = listOf(one, two.copy(enabled = false))).allUnread().map { it.second.item.id })
    }
}
