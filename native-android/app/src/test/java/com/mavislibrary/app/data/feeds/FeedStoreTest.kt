package com.mavislibrary.app.data.feeds

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class FeedStoreTest {
    @get:Rule val temp = TemporaryFolder()
    private fun found(n: Int) = DiscoveryResult.Found("https://example.com/$n.xml", "Site $n", 3, null, "https://example.com/")
    @Test fun addDedupeLimitRenameDisableRemoveAndPersistence() = runTest {
        val data = PreferenceDataStoreFactory.create(scope = backgroundScope) { temp.root.resolve("feeds.preferences_pb") }
        val store = FeedStore(data) { 42L }
        val one = store.add(found(1))
        assertEquals(42L, one.addedAt)
        assertEquals(one.id, store.add(found(1)).id)
        assertEquals(1, store.sources.first().size)
        store.rename(one.id, "Mine")
        store.setEnabled(one.id, false)
        assertEquals("Mine", store.sources.first().single().name)
        assertFalse(store.sources.first().single().enabled)
        store.add(found(1))
        assertTrue(store.sources.first().single().enabled)
        (2..20).forEach { store.add(found(it)) }
        assertTrue(runCatching { store.add(found(21)) }.isFailure)
        assertEquals(20, store.sources.first().size)
        store.remove(one.id)
        store.add(found(21))
        assertEquals(store.sources.first(), FeedStore(data).sources.first())
        assertTrue(runCatching { store.rename(one.id, " ") }.isFailure)
    }
}
