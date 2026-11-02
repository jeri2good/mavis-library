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
        val damaged = runCatching { UpdateDownloader(client, dir, attempts = 1).download(info.copy(sha256 = "0".repeat(64))) }
        assertTrue(damaged.exceptionOrNull()?.message!!.contains("damaged"))
        assertTrue(dir.list()!!.isEmpty())
    }

    @Test fun droppedConnectionResumesFromWhereItStoppedInsteadOfStartingOver() = runBlocking {
        val dir = temp.newFolder("resume")
        val cut = apk.size / 2 // DISCONNECT_DURING_RESPONSE_BODY drops after half the body
        // 1st response: promises the whole APK but the connection drops after `cut` bytes (J's "connection reset").
        server.enqueue(MockResponse().setBody(Buffer().write(apk))
            .setSocketPolicy(okhttp3.mockwebserver.SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY))
        // 2nd response: the server honours the Range request with only the missing bytes.
        server.enqueue(MockResponse().setResponseCode(206).setBody(Buffer().write(apk.copyOfRange(cut, apk.size)))
            .setHeader("Content-Range", "bytes $cut-${apk.size - 1}/${apk.size}"))
        val info = UpdateInfo(106, "0.7.18", server.url("/mavis.apk").toString(), apkSha)
        val file = UpdateDownloader(client, dir, attempts = 3, retryDelayMs = 1).download(info)
        assertArrayEquals(apk, file.readBytes())
        assertNull(server.takeRequest().getHeader("Range"))
        assertEquals("bytes=$cut-", server.takeRequest().getHeader("Range"))
        assertEquals(listOf("mavis-106.apk"), dir.list()!!.toList())
    }

    @Test fun serverThatIgnoresRangeStartsCleanAndStillVerifies() = runBlocking {
        val dir = temp.newFolder("norange")
        val info = UpdateInfo(107, "0.7.19", server.url("/mavis.apk").toString(), apkSha)
        // A stale partial for this exact build; the server answers 200 (whole file), so it must overwrite, not append.
        File(dir, "mavis-107-${apkSha.take(16)}.apk.part").writeBytes(ByteArray(5_000) { 7 })
        server.enqueue(MockResponse().setBody(Buffer().write(apk)))
        val file = UpdateDownloader(client, dir, attempts = 1).download(info)
        assertArrayEquals(apk, file.readBytes())
        assertEquals("bytes=5000-", server.takeRequest().getHeader("Range"))
    }

    @Test fun keepsPartialProgressWhenItFinallyGivesUp() = runBlocking {
        val dir = temp.newFolder("giveup")
        val info = UpdateInfo(108, "0.7.20", server.url("/mavis.apk").toString(), apkSha)
        repeat(2) {
            server.enqueue(MockResponse().setBody(Buffer().write(apk))
                .setSocketPolicy(okhttp3.mockwebserver.SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY))
        }
        val failed = runCatching { UpdateDownloader(client, dir, attempts = 2, retryDelayMs = 1).download(info) }
        assertTrue(failed.exceptionOrNull()?.message!!.contains("progress is saved"))
        val part = File(dir, "mavis-108-${apkSha.take(16)}.apk.part")
        assertTrue(part.exists() && part.length() > 0)
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
