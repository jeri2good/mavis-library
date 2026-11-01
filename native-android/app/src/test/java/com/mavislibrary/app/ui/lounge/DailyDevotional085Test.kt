package com.mavislibrary.app.ui.lounge

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.mavislibrary.app.data.briefing.BriefingSettings
import com.mavislibrary.app.data.podcast.PodcastJob
import com.mavislibrary.app.data.podcast.podcastJson
import com.mavislibrary.app.data.podsub.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DailyDevotional085Test {
    @get:Rule val compose = createComposeRule()

    /** Fake podsub gateway: records every PUT; never touches the network. */
    private class Api(var document: SubscriptionDocument = SubscriptionDocument()) : PodsubService {
        val puts = mutableListOf<SubscriptionDocument>()
        override suspend fun get() = response()
        fun response() = podcastJson.decodeFromString<SubscriptionResponse>(podcastJson.encodeToString(SubscriptionDocument.serializer(), document))
        override suspend fun put(document: SubscriptionDocument): SubscriptionResponse { puts += document; this.document = document; return response() }
        override suspend fun today() = emptyList<PodcastJob>()
        override suspend fun runs() = emptyList<SubscriptionRun>()
        override suspend fun runNow() = SubscriptionRun("now", status = "ok")
        override suspend fun previewDevotional(url: String) = DevotionalPreview("Alpha and Omega", "Revelation 22:12-21", url)
    }
    private val migration = object : MigrationState { override suspend fun followed(account: String) = emptyList<String>(); override suspend fun complete(account: String, ids: List<String>) {} }
    private fun vm(api: Api) = PodsubViewModel(PodsubRepository(api, migration, { "owner" }, { emptyList() }, { BriefingSettings() }))
    private val loaded = PodsubUiState(document = SubscriptionDocument(schedule = SubscriptionSchedule(timezone = "America/New_York")))

    @Test fun selectedTopicChipIsFilledAndExclusiveAndStartSendsTopicAndTime() {
        val started = mutableListOf<Pair<DevotionalRule, LocalTime>>()
        compose.setContent { MaterialTheme { DailyDevotionalContent(loaded, topicFirst = true, onPreview = {}, onStart = { r, t, _ -> started += r to t }, onBack = {}) } }
        compose.onNodeWithTag("daily_start").assertIsNotEnabled()
        compose.onNodeWithTag("topic_chip_Anxiety").performScrollTo().performClick()
        compose.onNodeWithTag("topic_chip_Anxiety").assertIsSelected()
        compose.onNodeWithTag("source_today").assertIsNotSelected()
        compose.onNodeWithText("A daily Bible lesson on Anxiety").assertExists()
        compose.onNodeWithTag("topic_chip_Faith").performScrollTo().performClick()
        compose.onNodeWithTag("topic_chip_Faith").assertIsSelected()
        compose.onNodeWithTag("topic_chip_Anxiety").assertIsNotSelected()
        compose.onNodeWithText("7:00 PM").assertExists()
        compose.onNodeWithTag("daily_start").assertIsEnabled().performClick()
        val (rule, time) = started.single()
        assertEquals("topic", rule.kind); assertEquals("Faith", rule.topic); assertEquals("Faith", rule.name)
        assertEquals(LocalTime.of(19, 0), time)
    }

    @Test fun siteModePreviewsTodayDevotionalAndWaitsForItBeforeStart() {
        val previews = mutableListOf<String>()
        var state by androidx.compose.runtime.mutableStateOf(loaded)
        compose.setContent { MaterialTheme { DailyDevotionalContent(state, topicFirst = false, onPreview = { previews += it }, onStart = { _, _, _ -> }, onBack = {}) } }
        compose.onNodeWithTag("source_today").assertIsSelected()
        compose.runOnIdle { assertEquals(listOf(TODAY_DEVOTIONAL_URL), previews) }
        compose.onNodeWithTag("daily_start").assertIsNotEnabled()
        state = loaded.copy(previewUrl = TODAY_DEVOTIONAL_URL, devotionalPreview = DevotionalPreview("Alpha and Omega", "Revelation 22:12-21", TODAY_DEVOTIONAL_URL))
        compose.onNodeWithText("Alpha and Omega").assertExists(); compose.onNodeWithText("Revelation 22:12-21").assertExists()
        compose.onNodeWithTag("daily_start").assertIsEnabled()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun startCallsAddDevotionalWithChosenTopicAndTimeAndTurnsDeliveryOn() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            // Not the first devotional, with delivery switched off: Start must still turn it on.
            val api = Api(SubscriptionDocument(schedule = SubscriptionSchedule(timezone = "America/New_York"),
                devotionals = listOf(DevotionalRule("td", "site", "Today Devotional", url = TODAY_DEVOTIONAL_URL)),
                devotionalSchedule = SubscriptionSchedule(enabled = false, readyBy = "19:00", timezone = "UTC")))
            val model = vm(api)
            model.refresh(); advanceUntilIdle()
            var saved = false
            model.startDevotional(DevotionalRule("new1", "topic", "Gratitude", topic = "Gratitude"), "06:30", "America/Chicago") { saved = true }
            advanceUntilIdle()
            assertTrue(saved)
            val put = api.puts.single()
            assertEquals(listOf("td", "new1"), put.devotionals.map { it.id })
            assertEquals("Gratitude", put.devotionals.last().topic)
            assertEquals("06:30", put.devotionalSchedule.readyBy)
            assertEquals("America/Chicago", put.devotionalSchedule.timezone)
            assertTrue(put.devotionalSchedule.enabled)
            assertFalse(model.state.value.dirty)
            // Starting the same site again reuses its rule instead of duplicating it.
            model.startDevotional(DevotionalRule("dup", "site", "Today Devotional", url = TODAY_DEVOTIONAL_URL), "07:00", "America/Chicago")
            advanceUntilIdle()
            assertEquals(listOf("new1", "td"), api.puts.last().devotionals.map { it.id })
        } finally { Dispatchers.resetMain() }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun sixthDevotionalIsRefusedWithoutSaving() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val api = Api(SubscriptionDocument(devotionals = (1..5).map { DevotionalRule("t$it", "topic", "T$it", topic = "T$it") }))
            val model = vm(api); model.refresh(); advanceUntilIdle()
            model.startDevotional(DevotionalRule("x", "topic", "Grief", topic = "Grief"), "19:00", "UTC"); advanceUntilIdle()
            assertTrue(api.puts.isEmpty())
            assertTrue(model.state.value.error!!.contains("up to 5"))
        } finally { Dispatchers.resetMain() }
    }

    @Test fun firstDeliveryLabelAndDefaults() {
        val zone = ZoneId.of("America/New_York")
        val morning = ZonedDateTime.of(2026, 11, 1, 9, 0, 0, 0, zone)
        assertEquals("First one: tonight 7:00 PM", firstDeliveryLabel(LocalTime.of(19, 0), subscriptionDays, morning))
        assertEquals("First one: tomorrow 7:00 PM", firstDeliveryLabel(LocalTime.of(19, 0), subscriptionDays, morning.withHour(20)))
        assertEquals("First one: today 10:00 AM", firstDeliveryLabel(LocalTime.of(10, 0), subscriptionDays, morning))
        assertEquals("First one: Wednesday 7:00 PM", firstDeliveryLabel(LocalTime.of(19, 0), listOf("wed"), morning))
        assertEquals(LocalTime.of(19, 0), defaultDevotionalTime(null))
        assertEquals(LocalTime.of(19, 0), defaultDevotionalTime(SubscriptionDocument(devotionalSchedule = SubscriptionSchedule(readyBy = "06:00"))))
        assertEquals(LocalTime.of(6, 0), defaultDevotionalTime(SubscriptionDocument(devotionals = listOf(DevotionalRule("a", "topic", "A", topic = "A")),
            devotionalSchedule = SubscriptionSchedule(readyBy = "06:00"))))
        assertEquals("every day", daysLabel(subscriptionDays)); assertEquals("Mon, Fri", daysLabel(listOf("mon", "fri")))
    }
}
