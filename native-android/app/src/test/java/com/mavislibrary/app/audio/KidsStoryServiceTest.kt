package com.mavislibrary.app.audio

import android.os.Bundle
import android.os.Looper
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionCommand
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class KidsStoryServiceTest {
    @Test fun adultAndKidsSessionsCoexistInEitherCreationOrder() {
        for (kidsFirst in listOf(false, true)) {
            val adult = Robolectric.buildService(PlaybackService::class.java)
            val kids = Robolectric.buildService(KidsStoryService::class.java)
            if (kidsFirst) { kids.create(); adult.create() }
            else { adult.create(); kids.create() }
            try {
                val adultSession = PlaybackService::class.java.getDeclaredField("librarySession")
                    .apply { isAccessible = true }.get(adult.get()) as MediaSession
                val kidsSession = KidsStoryService::class.java.getDeclaredField("session")
                    .apply { isAccessible = true }.get(kids.get()) as MediaSession
                assertEquals("kids-story", kidsSession.id)
                assertNotEquals(adultSession.id, kidsSession.id)
                val adultController = MediaController.Builder(adult.get(), adultSession.token).buildAsync()
                val kidsController = MediaController.Builder(kids.get(), kidsSession.token).buildAsync()
                try {
                    shadowOf(Looper.getMainLooper()).idle()
                    assertTrue(adultController.isDone)
                    assertTrue(kidsController.isDone)
                    assertTrue(adultController.get().isConnected)
                    assertTrue(kidsController.get().isConnected)
                    assertTrue(adultController.get().isCommandAvailable(Player.COMMAND_SET_MEDIA_ITEM))
                    assertFalse(kidsController.get().isCommandAvailable(Player.COMMAND_SET_MEDIA_ITEM))
                } finally {
                    MediaController.releaseFuture(kidsController)
                    MediaController.releaseFuture(adultController)
                }
            } finally {
                if (kidsFirst) { adult.destroy(); kids.destroy() }
                else { kids.destroy(); adult.destroy() }
            }
        }
    }

    @Test fun sessionHasPlaybackCommandsButCannotReplaceKidsMedia() {
        val service = Robolectric.buildService(KidsStoryService::class.java).create()
        val session = KidsStoryService::class.java.getDeclaredField("session").apply { isAccessible = true }
            .get(service.get()) as MediaSession
        val future = MediaController.Builder(ApplicationProvider.getApplicationContext(), session.token).buildAsync()
        try {
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue("Local controller should connect", future.isDone)
            val controller = future.get()
            assertTrue(controller.isCommandAvailable(Player.COMMAND_PLAY_PAUSE))
            assertFalse(controller.isCommandAvailable(Player.COMMAND_SET_MEDIA_ITEM))
            assertFalse(controller.isCommandAvailable(Player.COMMAND_CHANGE_MEDIA_ITEMS))
            val args = Bundle().apply { putInt("minutes", 10) }
            controller.sendCustomCommand(SessionCommand(KidsStoryService.SLEEP, Bundle.EMPTY), args)
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(10, KidsStoryService.state.value.sleepMinutes)
            shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMinutes(10))
            assertEquals(0, KidsStoryService.state.value.sleepMinutes)
            assertFalse(KidsStoryService.state.value.playing)
        } finally {
            MediaController.releaseFuture(future)
            service.destroy()
        }
        assertNull(KidsStoryService.state.value.story)
    }
}
