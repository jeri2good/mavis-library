package com.mavislibrary.app.data.kids

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class KidsPersistenceReviewTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun lockoutAndEnabledStateSurviveDataStoreRecreation() = runBlocking {
        val file = File(tmp.root, "kids.preferences_pb")
        var now = 1_000L
        val firstScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val prefs = KidsPrefs(PreferenceDataStoreFactory.create(scope = firstScope) { file }) { now }
            prefs.start("Sam", 15, "1234")
            repeat(4) { assertEquals(PinResult.Wrong, prefs.leave("0000")) }
            assertEquals(PinResult.Locked(30), prefs.leave("0000"))
            assertTrue(prefs.state.first().enabled)
        } finally {
            firstScope.cancel()
        }

        val secondScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val store = PreferenceDataStoreFactory.create(scope = secondScope) { file }
            val prefs = KidsPrefs(store) { now }
            assertTrue(prefs.state.first().enabled)
            assertEquals(PinResult.Locked(30), prefs.leave("1234"))
            assertTrue(prefs.state.first().enabled)
            val values = store.data.first().asMap().values
            assertFalse(values.any { it == "1234" })
            assertTrue(values.any { it is String && it.matches(Regex("[0-9a-f]{32}:[0-9a-f]{64}")) })
            now += 29_999
            assertEquals(PinResult.Locked(1), prefs.leave("1234"))
            now++
            assertEquals(PinResult.Wrong, prefs.leave("0000"))
            assertEquals(PinResult.Correct, prefs.leave("1234"))
            assertFalse(prefs.state.first().enabled)
        } finally {
            secondScope.cancel()
        }
    }
}
