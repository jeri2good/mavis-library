package com.mavislibrary.app.audio

import androidx.media3.session.MediaSession
import androidx.test.core.app.ApplicationProvider
import com.mavislibrary.app.MavisApp
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PlaybackServiceTest {
    @Test fun `service creates a library session with stable browse root`() {
        val app = ApplicationProvider.getApplicationContext<MavisApp>()
        assertNotNull(app.container)
        val controller = Robolectric.buildService(PlaybackService::class.java).create()
        val service = controller.get()
        val session = service.onGetSession(MediaSession.ControllerInfo.LEGACY_CONTROLLER_INFO)
        assertNotNull(session)
        assertEquals("mavis_root", PlaybackService.ROOT_ID)
        controller.destroy()
    }
}
