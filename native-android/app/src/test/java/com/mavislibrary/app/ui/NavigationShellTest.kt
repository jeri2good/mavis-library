package com.mavislibrary.app.ui

import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.mavislibrary.app.MainActivity
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class NavigationShellTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    private fun waitForTag(tag: String) = compose.waitUntil(5_000) {
        compose.onAllNodes(androidx.compose.ui.test.hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty()
    }

    @Test
    fun tabsNavigateAndBackReturnsToStartThenExits() {
        // The app opens on Search, which now starts with the curated shelves instead of an empty page.
        waitForTag("search_field")
        compose.onNodeWithTag("tab_search").assertIsSelected()

        compose.onNodeWithTag("tab_settings").performClick()
        waitForTag("settings")
        compose.onNodeWithTag("about_version").performScrollTo().assertExists()
        compose.activityRule.scenario.onActivity { assertTrue(it.onBackPressedDispatcher.hasEnabledCallbacks()) }

        compose.onNodeWithTag("look_midnight").performScrollTo().performClick()
        compose.waitForIdle()

        compose.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        waitForTag("tab_search")
        compose.onNodeWithTag("tab_search").assertIsSelected()

        // At the start tab nothing in the app intercepts Back, so the system default (leave the app) applies.
        compose.activityRule.scenario.onActivity { assertFalse(it.onBackPressedDispatcher.hasEnabledCallbacks()) }
    }

    @Test
    fun showcaseOpensFromSettingsAndBackCloses() {
        waitForTag("tab_settings")
        compose.onNodeWithTag("tab_settings").performClick()
        waitForTag("open_showcase")
        compose.onNodeWithTag("open_showcase").performScrollTo().performClick()
        waitForTag("showcase")
        compose.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        waitForTag("settings")
    }
}
