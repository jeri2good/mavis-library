package com.mavislibrary.app.voice

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.*
import org.junit.Test

class VoicePlatformTest {
    @Test fun `manifest keeps microphone optional with only existing recording permission`() {
        val doc = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }.newDocumentBuilder().parse(File("src/main/AndroidManifest.xml"))
        val ns = "http://schemas.android.com/apk/res/android"
        val permissions = doc.getElementsByTagName("uses-permission")
        val names = (0 until permissions.length).map { (permissions.item(it) as org.w3c.dom.Element).getAttributeNS(ns, "name") }
        assertEquals(1, names.count { it == "android.permission.RECORD_AUDIO" })
        assertFalse(names.contains("android.permission.FOREGROUND_SERVICE_MICROPHONE"))
        assertFalse(names.contains("android.permission.CAPTURE_AUDIO_OUTPUT"))
        val features = doc.getElementsByTagName("uses-feature")
        val mic = (0 until features.length).map { features.item(it) as org.w3c.dom.Element }.single { it.getAttributeNS(ns, "name") == "android.hardware.microphone" }
        assertEquals("false", mic.getAttributeNS(ns, "required"))
        assertTrue(doc.getElementsByTagName("queries").length > 0)
    }
    @Test fun `long repeats and release trigger once while short press stays playback`() {
        val gesture = HeadsetGesture(); var starts = 0; var shorts = 0
        fun event(down: Boolean, ms: Long, long: Boolean = false) = gesture.handle(down, !down, ms, long, trigger = { starts++ }, shortPress = { shorts++ })
        event(true, 0); event(true, 500, true); event(true, 1000); event(false, 1200)
        assertEquals(1, starts); assertEquals(0, shorts)
        event(true, 0); event(false, 100)
        assertEquals(1, shorts)
        event(true, 0); event(false, 600)
        assertEquals(2, starts)
    }
    @Test fun `canceled long down cannot start microphone`() {
        HeadsetGesture().handle(true, false, 800, true, canceled = true, trigger = { fail() }, shortPress = { fail() })
    }
    @Test fun `canceled press cannot fire or toggle`() {
        val g = HeadsetGesture()
        g.handle(true, false, 0, false, trigger = { fail() }, shortPress = { fail() })
        g.handle(false, true, 800, false, canceled = true, trigger = { fail() }, shortPress = { fail() })
    }
}
