package com.mavislibrary.app.audio

import android.app.Notification
import android.app.NotificationManager
import android.os.Bundle
import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaController
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
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
    @Test fun kidsNotificationDoesNotOverwriteOrCancelAdultNotification() {
        val adult = Robolectric.buildService(PlaybackService::class.java).create()
        val kids = Robolectric.buildService(KidsStoryService::class.java).create()
        try {
            val adultSession = PlaybackService::class.java.getDeclaredField("librarySession")
                .apply { isAccessible = true }.get(adult.get()) as MediaSession
            val kidsSession = KidsStoryService::class.java.getDeclaredField("session")
                .apply { isAccessible = true }.get(kids.get()) as MediaSession
            fun update(service: MediaSessionService, session: MediaSession) {
                val result = service.onUpdateNotificationAsync(session, false)
                shadowOf(Looper.getMainLooper()).idle()
                assertTrue("Notification update should complete", result.isDone)
                result.get()
            }
            fun post(service: MediaSessionService, session: MediaSession, title: String) {
                service.setShowNotificationForIdlePlayer(MediaSessionService.SHOW_NOTIFICATION_FOR_IDLE_PLAYER_ALWAYS)
                // An idle local item exercises Media3 notifications without opening any audio source.
                session.player.setMediaItem(MediaItem.Builder().setUri("file:///notification-test.mp3")
                    .setMediaMetadata(MediaMetadata.Builder().setTitle(title).build()).build())
                service.addSession(session)
                shadowOf(Looper.getMainLooper()).idle()
                update(service, session)
            }
            val manager = adult.get().getSystemService(NotificationManager::class.java)
            post(adult.get(), adultSession, "Adult book")
            post(kids.get(), kidsSession, "Kids story")
            val notifications = manager.activeNotifications.associateBy { it.id }
            assertEquals(setOf(DefaultMediaNotificationProvider.DEFAULT_NOTIFICATION_ID, 1002), notifications.keys)
            val adultNotification = notifications.getValue(DefaultMediaNotificationProvider.DEFAULT_NOTIFICATION_ID).notification
            val kidsNotification = notifications.getValue(1002).notification
            assertEquals("Adult book", adultNotification.extras.getCharSequence(Notification.EXTRA_TITLE))
            assertEquals("Kids story", kidsNotification.extras.getCharSequence(Notification.EXTRA_TITLE))
            assertEquals(DefaultMediaNotificationProvider.DEFAULT_CHANNEL_ID, adultNotification.channelId)
            assertEquals("kids_story_playback", kidsNotification.channelId)
            assertEquals("Kids stories and Long Listen",
                manager.getNotificationChannel(kidsNotification.channelId).name.toString())
            update(adult.get(), adultSession)
            assertEquals(2, manager.activeNotifications.size)
            kidsSession.player.clearMediaItems()
            shadowOf(Looper.getMainLooper()).idle()
            update(kids.get(), kidsSession)
            assertEquals(listOf(DefaultMediaNotificationProvider.DEFAULT_NOTIFICATION_ID),
                manager.activeNotifications.map { it.id })
        } finally {
            kids.destroy()
            adult.destroy()
        }
    }

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
