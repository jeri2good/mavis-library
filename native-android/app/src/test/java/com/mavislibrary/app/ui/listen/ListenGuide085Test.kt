package com.mavislibrary.app.ui.listen

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.mavislibrary.app.data.prefs.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ListenGuide085Test {
    @get:Rule val compose = createComposeRule()
    @get:Rule val temp = TemporaryFolder()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private fun store() = SettingsStore(PreferenceDataStoreFactory.create(scope = scope) { temp.root.resolve("guide.preferences_pb") })
    @After fun close() = scope.cancel()

    @Test fun threeCardsAdvanceWithNextAndFinishWithDone() {
        var done = 0
        compose.setContent { MaterialTheme { ListenGuide(onDone = { done++ }) } }
        listenGuideCards.forEachIndexed { i, card ->
            compose.onNodeWithTag("guide_title").assertTextEquals(card.title)
            compose.onNodeWithTag("guide_skip").assertExists()
            compose.onNodeWithTag("guide_next").assertTextEquals(if (i == listenGuideCards.lastIndex) "Done" else "Next").performClick()
        }
        assertEquals(1, done)
    }

    @Test fun guideIsShownOnceThenSkipStoresTheFlag() {
        val settings = store()
        assertFalse(runBlocking { settings.listenGuideSeen.first() })
        var visit by mutableStateOf(0)
        compose.setContent { MaterialTheme { androidx.compose.runtime.key(visit) { ListenGuideHost(settings) } } }
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("listen_guide").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("guide_skip").performClick()
        compose.onNodeWithTag("listen_guide").assertDoesNotExist()
        compose.waitUntil(5_000) { runBlocking { settings.listenGuideSeen.first() } }
        // A fresh visit (new composition, same stored flag) never shows it again.
        visit++
        compose.waitForIdle()
        compose.onNodeWithTag("listen_guide").assertDoesNotExist()
        assertTrue(runBlocking { settings.listenGuideSeen.first() })
    }
}
