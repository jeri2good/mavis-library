package com.mavislibrary.app.work

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.mavislibrary.app.data.briefing.*
import com.mavislibrary.app.data.podcast.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PodcastReady084Test {
    class Store : ReadyHistoryStore { var value = ReadyHistory(); override suspend fun read(account: String) = value; override suspend fun write(account: String, value: ReadyHistory) { this.value = value } }
    private val now = Instant.parse("2026-10-31T12:00:00Z").toEpochMilli()
    private fun job(status: String = "queued", id: String = "j") = PodcastJob(id, status, title = "Hebrews 12:1-3 — Faith", kind = "chapter", style = "deep-dive", durationSec = 420, createdAt = Instant.ofEpochMilli(now).toString(), error = "Studio reason")
    @Test fun readyDownloadsOnceNotifiesWithPlayAndPersistsAcrossRunnerInstances() = runTest {
        val store = Store(); val notices = mutableListOf<ReadyNotice>(); var downloads = 0
        suspend fun run() = PodcastReadyRunner(store) { now }.run("a", listOf(job()), { job("ready") }, { downloads++ }, { true }, { notices += it })
        run(); run(); assertEquals(1, downloads); assertEquals(1, notices.size)
        assertEquals("Bible lesson · 7 min · saved for offline listening", notices.single().text)
        val context = ApplicationProvider.getApplicationContext<Context>()
        val n = PodcastReadyNotifications.build(context, notices.single())
        assertEquals("Play", n.actions.single().title)
        assertEquals(PodcastPlaybackAction.PLAY, shadowOf(n.actions.single().actionIntent).savedIntent.action)
        assertEquals(com.mavislibrary.app.MainActivity.LOUNGE, shadowOf(n.contentIntent).savedIntent.getStringExtra(com.mavislibrary.app.MainActivity.WIDGET_DESTINATION))
        assertTrue(n.actions.single().actionIntent.isImmutable)
    }
    @Test fun failedReasonAndSixHoursEachOnceThenReady() = runTest {
        val store = Store(); var time = now; var status = "queued"; val notices = mutableListOf<ReadyNotice>()
        suspend fun run() = PodcastReadyRunner(store) { time }.run("a", listOf(job()), { job(status) }, {}, { true }, { notices += it })
        run(); time += PodcastReadyRunner.SIX_HOURS; run(); run(); assertEquals(listOf("working"), notices.map { it.outcome })
        status = "failed"; run(); run(); assertEquals("Studio reason", notices.last().text)
        status = "ready"; run(); run(); assertEquals(listOf("working", "failed", "ready"), notices.map { it.outcome })
    }
    @Test fun firstRunSuppressesBacklogButNotPendingCompletionAndPrunes() = runTest {
        val store = Store(); val notices = mutableListOf<ReadyNotice>()
        val runner = PodcastReadyRunner(store) { now }
        runner.run("a", listOf(job("ready", "old"), job()), { job("ready") }, {}, { true }, { notices += it })
        assertEquals(listOf("j"), notices.map { it.job.id })
        store.value = store.value.copy(outcomes = store.value.outcomes + ("stale/ready" to now - PodcastReadyRunner.RETAIN - 1))
        runner.run("a", emptyList(), { error("no HTTP") }, {}, { true }, {})
        assertFalse("stale/ready" in store.value.outcomes)
    }
    @Test fun deniedPermissionAndKidsGateDoNotCrashOrFetch() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        shadowOf(context as android.app.Application).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val manager = context.getSystemService(NotificationManager::class.java); manager.cancelAll()
        PodcastReadyNotifications.post(context, ReadyNotice(job("ready"), "ready", "Ready", "Saved"))
        assertEquals(0, shadowOf(manager).allNotifications.size)
        PodcastReadyRunner(Store()) { now }.run("a", listOf(job()), { error("Kids must not fetch") }, { error("No download") }, { false }, { error("No notification") })
    }
    @Test fun downloadFailureRetriesWithoutLosingOutcomeAndActionsRoute() = runTest {
        val store = Store(); var fail = true; var notices = 0
        suspend fun run() = PodcastReadyRunner(store) { now }.run("a", listOf(job()), { job("ready") }, { if (fail) error("offline") }, { true }, { notices++ })
        assertTrue(run()); fail = false; assertFalse(run()); assertEquals(1, notices)
        assertEquals("pause", playbackAction(true,true,true)); assertEquals("resume",playbackAction(false,true,true)); assertEquals("load",playbackAction(true,false,true))
    }
}
