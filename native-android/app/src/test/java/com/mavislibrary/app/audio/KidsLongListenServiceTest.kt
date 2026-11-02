package com.mavislibrary.app.audio

import android.os.Bundle
import android.os.Looper
import android.os.SystemClock
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaController
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionCommand
import com.mavislibrary.app.MavisApp
import com.mavislibrary.app.data.kids.*
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class KidsLongListenServiceTest {
    private fun field(service: KidsStoryService, name: String) =
        KidsStoryService::class.java.getDeclaredField(name).apply { isAccessible = true }.get(service)
    private fun invoke(service: KidsStoryService, name: String) =
        KidsStoryService::class.java.getDeclaredMethod(name).apply { isAccessible = true }.invoke(service)
    private fun await(condition: () -> Boolean) {
        val until = System.nanoTime() + 10_000_000_000L
        while (!condition() && System.nanoTime() < until) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(10)
        }
        assertTrue("Timed out waiting for service", condition())
    }
    private fun fixture(service: KidsStoryService): LongListenBook {
        val text = javaClass.getResource("/kids/real-longlisten-v1.json")!!.readText()
        File(StoryRepository(service).root, "longlisten.json").writeText(text)
        val book = LongListen.parse(text).books.first()
        // All chapters are local. These tests must never contact archive.org.
        val bytes = javaClass.getResourceAsStream("/fixtures/kids/audio/host/t2/01.mp3")!!.use { it.readBytes() }
        book.sections.indices.forEach { index ->
            File(service.filesDir, "audio/${book.version().id}/$index.mp3").apply {
                parentFile!!.mkdirs(); writeBytes(bytes)
            }
        }
        return book
    }
    private fun connect(service: KidsStoryService): com.google.common.util.concurrent.ListenableFuture<MediaController> {
        val session = field(service, "session") as MediaSession
        assertEquals("kids-story", session.id)
        return MediaController.Builder(service, session.token).buildAsync().also {
            await { it.isDone }
        }
    }
    private fun command(controller: MediaController, action: String, args: Bundle = Bundle.EMPTY) {
        controller.sendCustomCommand(SessionCommand(action, Bundle.EMPTY), args)
        shadowOf(Looper.getMainLooper()).idle()
    }
    @Test fun cachedBookResumesUsesLocalAudioAndKeepsChapterControlsContained() {
        val owner = Robolectric.buildService(KidsStoryService::class.java).create()
        val service = owner.get()
        val book = fixture(service)
        val app = service.application as MavisApp
        runBlocking {
            service.kidsPrefs().start("Reader", 15, "1234")
            app.container.resumePositionStore.save(ResumePosition("kids-long:${book.id}", 1, 400, 1f))
        }
        val future = connect(service)
        try {
            val controller = future.get()
            assertFalse(controller.isCommandAvailable(Player.COMMAND_SET_MEDIA_ITEM))
            command(controller, KidsStoryService.START_LONG, Bundle().apply { putString("id", book.id) })
            await { KidsStoryService.state.value.longBook != null }
            val player = field(service, "narration") as ExoPlayer
            assertEquals(1, KidsStoryService.state.value.page)
            assertEquals(400L, player.currentPosition)
            assertEquals("file", player.currentMediaItem!!.localConfiguration!!.uri.scheme)
            assertFalse((field(service, "music") as ExoPlayer).playWhenReady)
            command(controller, KidsStoryService.NEXT)
            assertEquals(2, KidsStoryService.state.value.page)
            command(controller, KidsStoryService.PREVIOUS)
            assertEquals(1, KidsStoryService.state.value.page)
            command(controller, KidsStoryService.STOP)
            runBlocking { (field(service, "saveJob") as Job).join() }
            assertNull(KidsStoryService.state.value.longBook)
            val saved = runBlocking { app.container.resumePositionStore.load("kids-long:${book.id}") }!!
            assertEquals(1, saved.chapterIndex)
            assertNull(runBlocking { app.container.resumePositionStore.load("kids-long:librivox-1461") })
        } finally {
            MediaController.releaseFuture(future)
            owner.destroy()
        }
    }
    @Test fun longTimersAcceptOnlyTheirDurationsAndEndOfChapterStopsAdvance() {
        val owner = Robolectric.buildService(KidsStoryService::class.java).create()
        val service = owner.get()
        val book = fixture(service)
        @Suppress("UNCHECKED_CAST")
        val mutable = field(service, "mutable") as MutableStateFlow<KidsStoryState>
        mutable.value = KidsStoryState(longBook = book)
        val future = connect(service)
        try {
            val controller = future.get()
            listOf(15, 30, 45, 60).forEach { minutes ->
                command(controller, KidsStoryService.SLEEP, Bundle().apply { putInt("minutes", minutes) })
                assertEquals(minutes, KidsStoryService.state.value.sleepMinutes)
                val remaining = (field(service, "deadline") as Long) - SystemClock.elapsedRealtime()
                assertTrue(remaining in (minutes * 60_000L - 100)..minutes * 60_000L)
            }
            command(controller, KidsStoryService.SLEEP, Bundle().apply { putInt("minutes", 10) })
            assertEquals(0, KidsStoryService.state.value.sleepMinutes)
            command(controller, KidsStoryService.SLEEP, Bundle().apply { putInt("minutes", -1) })
            invoke(service, "finishChapter")
            assertEquals(0, KidsStoryService.state.value.page)
            assertEquals(0, KidsStoryService.state.value.sleepMinutes)
            assertFalse(KidsStoryService.state.value.playing)
            command(controller, KidsStoryService.SLEEP, Bundle().apply { putInt("minutes", 15) })
            KidsStoryService::class.java.getDeclaredField("deadline").apply {
                isAccessible = true; set(service, SystemClock.elapsedRealtime())
            }
            (field(service, "timer") as Runnable).run()
            assertEquals(0, KidsStoryService.state.value.sleepMinutes)
            assertFalse((field(service, "narration") as ExoPlayer).playWhenReady)
            invoke(service, "finishChapter")
            assertEquals(1, KidsStoryService.state.value.page)
        } finally {
            MediaController.releaseFuture(future)
            owner.destroy()
        }
    }
    @Test fun disabledKidsModeCannotStartLongListen() {
        val owner = Robolectric.buildService(KidsStoryService::class.java).create()
        val service = owner.get()
        val book = fixture(service)
        runBlocking {
            service.kidsPrefs().start("Reader", 15, "1234")
            service.kidsPrefs().leave("1234")
        }
        val future = connect(service)
        try {
            command(future.get(), KidsStoryService.START_LONG, Bundle().apply { putString("id", book.id) })
            await { KidsStoryService.state.value.error != null }
            assertNull(KidsStoryService.state.value.longBook)
        } finally {
            MediaController.releaseFuture(future)
            owner.destroy()
        }
    }
}
