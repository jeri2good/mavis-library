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
    @Test fun listenOpensSignInGateAndSixTabs() {
        waitFor("tab_listen"); compose.onNodeWithTag("tab_listen").performClick()
        waitFor("podcast_lounge")
        compose.onNodeWithText("Open Podcast Lounge").assertDoesNotExist()
        compose.onNodeWithText("Sign in to make and hear podcasts").assertExists()
        compose.onNodeWithText("Find a free human-read audiobook").performScrollTo().assertExists()
        assertEquals(listOf("shelf", "search", "bible", "sites", "listen", "settings"), Tab.entries.map { it.route })
        compose.onNodeWithText("Sign in in Settings").performClick(); waitFor("settings")
        compose.onNodeWithTag("account_settings").performScrollTo().assertExists()
        compose.onNodeWithTag("podcast_settings").performScrollTo().performClick()
        waitFor("podcast_settings_page")
        compose.onNodeWithText("Sign in to manage your daily briefing and devotionals.").assertExists()
        compose.onNodeWithTag("podcast_defaults").performScrollTo().assertExists()
        compose.onNodeWithTag("tab_settings").assertIsSelected()
    }
    @Test fun loungeRouteAndNotificationDestinationRedirectToListen() {
        assertEquals("listen", loungeDestination(ROUTE_LOUNGE))
        assertEquals("settings", loungeDestination("settings"))
        waitFor("tab_settings"); compose.onNodeWithTag("tab_settings").performClick(); waitFor("settings")
        compose.activityRule.scenario.onActivity { activity ->
            MainActivity::class.java.getDeclaredMethod("onNewIntent", Intent::class.java).apply { isAccessible = true }
                .invoke(activity, Intent(activity, MainActivity::class.java).putExtra(MainActivity.WIDGET_DESTINATION, MainActivity.LOUNGE))
        }
        waitFor("podcast_lounge")
        compose.onNodeWithTag("tab_listen").assertIsSelected()
    }
    @Test fun sitesTabLinksToSubscriptionsAndKeepsFeedReading() {
        waitFor("tab_sites"); compose.onNodeWithTag("tab_sites").performClick(); waitFor("sites")
        compose.onNodeWithText("Podcast subscriptions in Settings").assertExists()
        compose.onNodeWithTag("briefing_now").assertDoesNotExist()
        compose.onNodeWithTag("add_site_card").assertExists()
        compose.onNodeWithTag("follow_site").performClick()
        compose.onNodeWithTag("site_address").assertExists()
    }
    @Test fun settingsHasNativeAccountRecoveryAndNoAnonymousApprovals() {
        waitFor("tab_settings"); compose.onNodeWithTag("tab_settings").performClick(); waitFor("settings")
        compose.onNodeWithTag("account_settings").performScrollTo()
        compose.onNodeWithTag("account_email").assertExists()
        compose.onNodeWithText("Create account").performScrollTo().performClick()
        compose.onNodeWithTag("account_submit").assertIsNotEnabled()
        compose.onNodeWithTag("account_mode_signin").performScrollTo().performClick()
        compose.onNodeWithText("Forgot password?").performScrollTo().performClick()
        compose.onNodeWithTag("account_recovery").assertExists()
        compose.onNodeWithText("New password").assertExists()
        compose.onNodeWithTag("account_confirm").assertExists()
        compose.onNodeWithText("Reset password").assertExists()
        compose.onNodeWithText("No recovery code? Ask the owner to reset your password.").assertExists()
        compose.onNodeWithTag("account_submit").assertIsNotEnabled()
        compose.onNodeWithTag("owner_approvals").assertDoesNotExist()
    }
    @Test fun httpsShareRequiresSignInAndKidsNeverReachLounge() {
        waitFor("tab_listen"); compose.waitForIdle(); share("https://example.org/article"); compose.waitForIdle()
        waitFor("podcast_lounge"); compose.onNodeWithTag("tab_listen").assertIsSelected()
        compose.onNodeWithText("Sign in to make and hear podcasts").assertExists()
        compose.onNodeWithText("Make podcast").assertDoesNotExist()
        val prefs = compose.activity.kidsPrefs()
        runBlocking { prefs.start("Sam", 15, "1234") }
        try {
            compose.waitUntil(5_000) { compose.onAllNodes(hasText("Hi, Sam!")).fetchSemanticsNodes().isNotEmpty() }
            share("https://example.org/article"); compose.waitForIdle()
            compose.onNodeWithTag("podcast_lounge").assertDoesNotExist()
            compose.onNodeWithText("Podcast this?").assertDoesNotExist()
            compose.onNodeWithTag("tab_listen").assertDoesNotExist()
            compose.onNodeWithTag("account_settings").assertDoesNotExist()
            compose.onNodeWithTag("owner_approvals").assertDoesNotExist()
        } finally { runBlocking { prefs.leave("1234") } }
    }
}
