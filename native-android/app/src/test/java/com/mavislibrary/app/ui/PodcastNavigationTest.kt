package com.mavislibrary.app.ui

import android.content.Intent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.mavislibrary.app.MainActivity
import com.mavislibrary.app.data.kids.kidsPrefs
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PodcastNavigationTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private fun waitFor(tag: String) = compose.waitUntil(5_000) { compose.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty() }
    private fun share(text: String) = compose.activityRule.scenario.onActivity { activity ->
        MainActivity::class.java.getDeclaredMethod("onNewIntent", Intent::class.java).apply { isAccessible = true }
            .invoke(activity, Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text))
    }
    @Test fun listenOpensLoungeWithBlankStudioAndOnlyFiveTabs() {
        waitFor("tab_listen"); compose.onNodeWithTag("tab_listen").performClick()
        compose.onNodeWithText("Open Podcast Lounge").performClick(); waitFor("podcast_lounge")
        compose.onNodeWithText("Podcasts are made by your Mavis studio at home. Add its address and key in Settings.").assertExists()
        assertEquals(5, Tab.entries.size)
        compose.onNodeWithText("Open Settings").performClick(); waitFor("settings")
        compose.onNodeWithTag("podcast_settings").performScrollTo().assertExists()
    }
    @Test fun httpsShareConfirmsBeforeCreationAndKidsNeverReachLounge() {
        waitFor("tab_listen"); compose.waitForIdle(); share("https://example.org/article"); compose.waitForIdle()
        waitFor("podcast_lounge"); compose.onNodeWithText("Podcast this?").assertExists()
        compose.onNodeWithText("Review podcast").performClick()
        val prefs = compose.activity.kidsPrefs()
        runBlocking { prefs.start("Sam", 15, "1234") }
        try {
            compose.waitUntil(5_000) { compose.onAllNodes(hasText("Hi, Sam!")).fetchSemanticsNodes().isNotEmpty() }
            share("https://example.org/article"); compose.waitForIdle()
            compose.onNodeWithTag("podcast_lounge").assertDoesNotExist()
            compose.onNodeWithText("Podcast this?").assertDoesNotExist()
            compose.onNodeWithTag("tab_listen").assertDoesNotExist()
        } finally { runBlocking { prefs.leave("1234") } }
    }
}
