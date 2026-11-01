package com.mavislibrary.app.ui.lounge

import android.app.Application
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import com.mavislibrary.app.AppContainer
import com.mavislibrary.app.data.podcast.PodcastDraft
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PodcastSourcePickerTest {
    @get:Rule val compose = createComposeRule()
    @Test fun newSheetPutsEveryDayFirstThenFiveOneTimeChoices() {
        assertEquals(listOf("Every day", "Make one now"), newSheetLanes.map { it.title })
        assertEquals(listOf(DAILY_SITE, DAILY_TOPIC, DAILY_BRIEFING), newSheetLanes.first().rows.map { it.key })
        assertEquals(sourceTypes.keys.toList(), newSheetLanes.last().rows.map { it.key })
        var picked = ""
        compose.setContent { MaterialTheme { Column(androidx.compose.ui.Modifier.verticalScroll(androidx.compose.foundation.rememberScrollState())) { NewSheetLanes { picked = it } } } }
        val daily = compose.onNodeWithTag("new_lane_daily").fetchSemanticsNode().positionInRoot.y
        val now = compose.onNodeWithTag("new_lane_now").fetchSemanticsNode().positionInRoot.y
        assertTrue("Every day is drawn above Make one now", daily < now)
        newSheetLanes.flatMap { it.rows }.forEach { row ->
            compose.onNodeWithTag("new_row_${row.key}").performScrollTo().performClick()
            assertEquals(row.key, picked)
        }
    }
    @Test fun topicCanBePickedWithoutWebViewOrNetwork() {
        val container = AppContainer(ApplicationProvider.getApplicationContext<Application>(), onlineOverride = { false })
        var draft: PodcastDraft? = null
        compose.setContent { MaterialTheme { PodcastSourcePicker("topic", container, emptyList(), emptyList(), null, {}, { draft = it }, {}) } }
        compose.onNodeWithText("What should the hosts talk about?").performTextInput("Ocean exploration")
        compose.onNodeWithText("Use topic").performScrollTo().performClick()
        assertEquals("Ocean exploration", draft?.text)
        assertEquals("topic", draft?.kind)
    }
    @Test fun emptyBookPickerExplainsHowToFillItWithoutWebView() {
        val container = AppContainer(ApplicationProvider.getApplicationContext<Application>(), onlineOverride = { false })
        compose.setContent { MaterialTheme { PodcastSourcePicker("book", container, emptyList(), emptyList(), null, {}, {}, {}) } }
        compose.onNodeWithText("Save a book to your shelf or import an EPUB to see it here.").assertExists()
        compose.onNodeWithText("Search free classics").assertExists()
    }
}
