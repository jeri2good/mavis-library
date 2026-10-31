package com.mavislibrary.app.ui.lounge

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.mavislibrary.app.data.podsub.*
import com.mavislibrary.app.data.podcast.*
import com.mavislibrary.app.data.briefing.BriefingSettings
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class Subscriptions084Test {
    @get:Rule val compose = createComposeRule()
    private class Api : PodsubService {
        var document = SubscriptionDocument()
        override suspend fun get() = response()
        fun response() = podcastJson.decodeFromString<SubscriptionResponse>(podcastJson.encodeToString(SubscriptionDocument.serializer(), document)).copy(updatedAt = "saved")
        override suspend fun put(document: SubscriptionDocument): SubscriptionResponse { this.document = document; return response() }
        override suspend fun today() = emptyList<PodcastJob>()
        override suspend fun runs() = emptyList<SubscriptionRun>()
        override suspend fun runNow() = SubscriptionRun("now", status = "ok")
        override suspend fun previewDevotional(url: String) = DevotionalPreview("Faith today", "Hebrews 12:1-3", url)
    }
    private fun show(api: Api): PodsubViewModel {
        val migration = object : MigrationState { override suspend fun followed(account: String) = emptyList<String>(); override suspend fun complete(account: String, ids: List<String>) {} }
        val vm = PodsubViewModel(PodsubRepository(api, migration, { "owner" }, { emptyList() }, { BriefingSettings() }))
        compose.setContent { val state by vm.state.collectAsState(); Column(Modifier.verticalScroll(rememberScrollState())) { PodsubSection(state, vm, onPlay = {}) } }
        compose.runOnIdle { vm.refresh() }; compose.waitUntil { vm.state.value.document != null && !vm.state.value.busy }
        return vm
    }
    @Test fun sectionsAndTopicFlowSaveThroughApi() {
        val api = Api(); val vm = show(api)
        compose.onNodeWithText("Daily briefing").assertExists(); compose.onNodeWithText("Devotionals").assertExists(); compose.onNodeWithText("Delivery").assertExists()
        compose.onNodeWithText("Add").performClick(); compose.onNodeWithText("Topic").performClick()
        compose.onNodeWithText("Anxiety").performClick(); compose.onNodeWithText("Add subscription").performClick()
        compose.onNodeWithText("Save subscriptions").performScrollTo().performClick()
        compose.waitUntil { !vm.state.value.busy && !vm.state.value.dirty }
        assertEquals("Anxiety", api.document.devotionals.single().topic)
    }
    @Test fun devotionalPreviewAndIndependentSchedule() {
        val api = Api(); val vm = show(api)
        compose.onNodeWithText("Add").performClick(); compose.onNodeWithText("Devotional site").performClick()
        compose.onNodeWithText("Today Daily Devotional").performClick()
        compose.waitUntil { vm.state.value.devotionalPreview != null && !vm.state.value.busy }
        compose.onNodeWithText("Hebrews 12:1-3").assertExists(); compose.onNodeWithText("Add subscription").performClick()
        compose.onNodeWithTag("Devotional delivery").performScrollTo().performClick(); compose.onNodeWithText("Set time").performClick()
        compose.onNodeWithText("Save subscriptions").performScrollTo().performClick(); compose.waitUntil { !vm.state.value.busy && !vm.state.value.dirty }
        assertEquals("site", api.document.devotionals.single().kind); assertTrue(api.document.devotionalSchedule.enabled); assertFalse(api.document.schedule.enabled)
    }
    @Test fun labelsUseScriptureAndMetadataAndArrivalUsesDeliveryTime() {
        fun job(title: String) = PodcastJob("a", "ready", title = title, kind = "chapter", style = "deep-dive")
        for (title in listOf("Hebrews 12:1-3 — Faith", "1 Corinthians 13 — Love", "Psalm 23 — Shepherd", "Bible - John 3")) assertEquals("Bible lesson", episodeKind(job(title)))
        assertEquals("Bible lesson", episodeKind(job("Theme").copy(meta = DevotionalMeta("d", "topic", "John 3:16-18"))))
        assertEquals("Deep dive", episodeKind(job("Technology")))
        assertEquals("Tonight 7:00 PM", subscriptionArrival("2026-10-31T18:00:00Z", SubscriptionSchedule(true, "19:00", timezone = "UTC"), Instant.parse("2026-10-31T10:00:00Z")))
    }
}
