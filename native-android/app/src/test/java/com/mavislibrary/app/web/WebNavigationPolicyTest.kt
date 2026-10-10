package com.mavislibrary.app.web

import android.net.Uri
import androidx.work.NetworkType
import com.mavislibrary.app.work.DownloadScheduler
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class WebNavigationPolicyTest {
    @Test
    fun onlyTheBundledOriginLoadsInPlace() {
        assertTrue(WebNavigationPolicy.isAppUrl(Uri.parse("https://localhost/#/bible")))
        assertTrue(WebNavigationPolicy.isAppUrl(Uri.parse("https://localhost/assets/a.js")))
        assertFalse(WebNavigationPolicy.isAppUrl(Uri.parse("http://localhost/")))
        assertFalse(WebNavigationPolicy.isAppUrl(Uri.parse("https://localhost:8443/")))
        assertFalse(WebNavigationPolicy.isAppUrl(Uri.parse("https://localhost.evil.com/")))
        assertFalse(WebNavigationPolicy.isAppUrl(Uri.parse("https://mavis-library.netlify.app/")))
    }

    @Test
    fun externalSchemesAreLimited() {
        assertTrue(WebNavigationPolicy.canOpenExternally(Uri.parse("https://www.gutenberg.org/")))
        assertTrue(WebNavigationPolicy.canOpenExternally(Uri.parse("mailto:a@b.c")))
        assertFalse(WebNavigationPolicy.canOpenExternally(Uri.parse("intent://x#Intent;end")))
        assertFalse(WebNavigationPolicy.canOpenExternally(Uri.parse("javascript:alert(1)")))
        assertFalse(WebNavigationPolicy.canOpenExternally(Uri.parse("file:///sdcard/x")))
    }

    @Test
    fun epubPickerAcceptsOctetStream() {
        assertArrayEquals(arrayOf("application/epub+zip", "application/octet-stream"), WebNavigationPolicy.mimeTypesFor(arrayOf(".epub")))
        assertArrayEquals(arrayOf("*/*"), WebNavigationPolicy.mimeTypesFor(arrayOf("")))
        assertArrayEquals(arrayOf("*/*"), WebNavigationPolicy.mimeTypesFor(null))
    }

    @Test
    fun nativeMarkerDeclaresAndroid() {
        val script = WebHost.nativeMarkerScript()
        assertTrue(script.startsWith("window.MavisNative = Object.freeze({"))
        assertTrue(script.contains("\"platform\":\"android\""))
        assertTrue(script.contains("\"version\":\""))
    }

    @Test
    fun downloadsAreWifiOnly() {
        assertEquals(NetworkType.UNMETERED, DownloadScheduler.constraints.requiredNetworkType)
        assertTrue(DownloadScheduler.constraints.requiresStorageNotLow())
    }
}
