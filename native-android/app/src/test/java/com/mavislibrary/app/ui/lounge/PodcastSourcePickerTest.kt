package com.mavislibrary.app.ui.lounge

import android.app.Application
import androidx.compose.foundation.layout.Column
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
    @Test fun typeCardsOfferFiveAccessibleChoices() {
        var picked = ""
        compose.setContent { MaterialTheme { Column { PodcastTypeCards { picked = it } } } }
        sourceTypes.forEach { (kind, title) ->
            compose.onNodeWithText(title).performClick()
            assertEquals(kind, picked)
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
