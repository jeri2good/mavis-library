package com.mavislibrary.app.update

import android.content.pm.PackageInstaller
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.security.MessageDigest

class UpdateTest {
    @get:Rule val temp = TemporaryFolder()
    private val server = MockWebServer()
    private val client = OkHttpClient()
    private val apk = ByteArray(300_000) { (it % 251).toByte() }
    private val apkSha = MessageDigest.getInstance("SHA-256").digest(apk).joinToString("") { "%02x".format(it) }
    @After fun tearDown() = server.shutdown()

    private fun manifest(code: Int = 105, url: String = "${RELEASE_DOWNLOADS}android-$code/mavis-0.7.5.apk", sha: String = apkSha) =
        """{"versionCode":$code,"versionName":"0.7.5","apkUrl":"$url","sha256":"$sha","notes":"Sites tab","publishedAt":"2026-10-20T07:00:00Z","extra":1}"""

    @Test fun manifestMustPointAtThisRepositorysReleases() {
        val info = UpdatePolicy.parse(manifest())
        assertEquals(105, info.versionCode)
        assertEquals("Sites tab", info.notes)
        listOf(
            manifest(url = "https://evil.example.com/mavis.apk"),
            manifest(url = "http://github.com/jeri2good/mavis-library/releases/download/a/mavis.apk"),
            manifest(url = "https://github.com/someone/else/releases/download/a/mavis.apk"),
            manifest(url = "${RELEASE_DOWNLOADS}../../x/mavis.apk"),
            manifest(url = "${RELEASE_DOWNLOADS}tag/mavis.zip"),
            manifest(sha = "ABC"),
            manifest(code = 0),
            "not json",
        ).forEach { assertTrue(it, runCatching { UpdatePolicy.parse(it) }.exceptionOrNull() is IOException) }
    }

    @Test fun checkerReturnsOnlyNewerReleases() = runBlocking {
        server.enqueue(MockResponse().setBody(manifest(105)))
        server.enqueue(MockResponse().setBody(manifest(105)))
        server.enqueue(MockResponse().setResponseCode(404))
        server.enqueue(MockResponse().setResponseCode(500))
        server.enqueue(MockResponse().setBody("x".repeat(70_000)))
        val url = server.url("/update.json").toString()
        assertEquals(105, UpdateChecker(client, url, 2).check()?.versionCode)
        assertNull(UpdateChecker(client, url, 105).check())
        assertNull(UpdateChecker(client, url, 2).check()) // nothing published yet
        assertTrue(runCatching { UpdateChecker(client, url, 2).check() }.exceptionOrNull() is IOException)
        assertTrue(runCatching { UpdateChecker(client, url, 2).check() }.exceptionOrNull() is IOException)
        assertEquals("no-cache", server.takeRequest().getHeader("Cache-Control"))
    }

    @Test fun downloadKeepsOnlyAnApkWhoseChecksumMatches() = runBlocking {
        val dir = temp.newFolder("updates")
        File(dir, "mavis-100.apk").writeText("old")
        server.enqueue(MockResponse().setBody(Buffer().write(apk)))
        server.enqueue(MockResponse().setBody(Buffer().write(apk)))
        val info = UpdateInfo(105, "0.7.5", server.url("/mavis.apk").toString(), apkSha)
        var last = 0L
        val file = UpdateDownloader(client, dir).download(info) { done, _ -> last = done }
        assertArrayEquals(apk, file.readBytes())
        assertEquals(apk.size.toLong(), last)
        assertEquals(listOf("mavis-105.apk"), dir.list()!!.toList())
        val damaged = runCatching { UpdateDownloader(client, dir).download(info.copy(sha256 = "0".repeat(64))) }
        assertTrue(damaged.exceptionOrNull()?.message!!.contains("damaged"))
        assertTrue(dir.list()!!.isEmpty())
    }

    @Test fun updaterAsksForPermissionThenDownloadsAndReportsAndroidsAnswer() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            repeat(2) { server.enqueue(MockResponse().setBody(Buffer().write(apk))) }
            var allowed = false
            val installed = mutableListOf<File>()
            val updater = AppUpdater(UpdateChecker(client, server.url("/none").toString(), 2), UpdateDownloader(client, temp.newFolder("u")), scope,
                canInstall = { allowed }, installApk = { installed += it })
            val info = UpdateInfo(105, "0.7.5", server.url("/mavis.apk").toString(), apkSha)
            updater.install(info)
            assertEquals(UpdateState.NeedsPermission(info), updater.state.value)
            allowed = true
            updater.install(info)
            withTimeout(5_000) { updater.state.first { it is UpdateState.Installing } }
            withTimeout(5_000) { while (installed.isEmpty()) kotlinx.coroutines.delay(10) }
            updater.installFinished(PackageInstaller.STATUS_FAILURE_ABORTED, null)
            assertEquals(UpdateState.Available(info), updater.state.value)
            updater.install(info)
            withTimeout(5_000) { updater.state.first { it is UpdateState.Installing } }
            updater.installFinished(PackageInstaller.STATUS_FAILURE_CONFLICT, "INSTALL_FAILED_UPDATE_INCOMPATIBLE")
            assertTrue((updater.state.value as UpdateState.Failed).message.contains("different key"))
        } finally { scope.cancel() }
    }
}
