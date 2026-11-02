package com.mavislibrary.app.readaloud

import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorOutputStream
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest

class KokoroModelStoreTest {
    @get:Rule val tmp = TemporaryFolder()
    private val server = MockWebServer()

    @Before fun start() = server.start()
    @After fun stop() = server.shutdown()

    private fun tarBz2(vararg files: Pair<String, String>): ByteArray {
        val bytes = ByteArrayOutputStream()
        TarArchiveOutputStream(BZip2CompressorOutputStream(bytes)).use { tar ->
            files.forEach { (name, body) ->
                val data = body.toByteArray()
                tar.putArchiveEntry(TarArchiveEntry(name).apply { size = data.size.toLong() })
                tar.write(data)
                tar.closeArchiveEntry()
            }
        }
        return bytes.toByteArray()
    }

    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private val good = tarBz2("m/model.bin" to "weights", "m/data/tokens.txt" to "tokens")
    private fun model(bytes: ByteArray = good) = KokoroModel("m", server.url("/m.tar.bz2").toString(), sha(bytes), bytes.size.toLong(), 1_000, listOf("model.bin", "data/tokens.txt"))
    private fun store(root: File, m: KokoroModel = model()) = KokoroModelStore(root, OkHttpClient(), m)
    private fun body(bytes: ByteArray) = Buffer().write(bytes)

    @Test fun downloadsVerifiesAndInstalls() = runTest {
        val root = tmp.newFolder()
        server.enqueue(MockResponse().setBody(body(good)))
        val store = store(root)
        assertNull(store.modelDir())
        store.download()
        assertEquals(KokoroState.Installed, store.state.value)
        assertEquals("weights", File(store.modelDir(), "model.bin").readText())
        assertFalse(File(root, "m.tar.bz2.part").exists())
        assertEquals(KokoroState.Installed, store(root).state.value)
    }

    @Test fun aWrongChecksumIsDeletedAndNeverInstalled() = runTest {
        val root = tmp.newFolder()
        val tampered = good.copyOf().also { it[it.lastIndex] = (it.last() + 1).toByte() }
        server.enqueue(MockResponse().setBody(body(tampered)))
        val store = store(root)
        store.download()
        assertEquals("The download was damaged. Try again.", (store.state.value as KokoroState.Failed).message)
        assertNull(store.modelDir())
        assertFalse(File(root, "m.tar.bz2.part").exists())
        assertFalse(File(root, "m").exists())
    }

    @Test fun resumesAPartialDownloadWithRange() = runTest {
        val root = tmp.newFolder()
        val split = good.size / 2
        File(root, "m.tar.bz2.part").writeBytes(good.copyOfRange(0, split))
        server.enqueue(MockResponse().setResponseCode(206).setHeader("Content-Range", "bytes $split-${good.size - 1}/${good.size}").setBody(body(good.copyOfRange(split, good.size))))
        val store = store(root)
        assertEquals(KokoroState.NotInstalled(split.toLong()), store.state.value)
        store.download()
        assertEquals("bytes=$split-", server.takeRequest().getHeader("Range"))
        assertEquals(KokoroState.Installed, store.state.value)
    }

    @Test fun aServerIgnoringRangeReplacesThePartialCopy() = runTest {
        val root = tmp.newFolder()
        File(root, "m.tar.bz2.part").writeBytes(good.copyOfRange(0, 5))
        server.enqueue(MockResponse().setBody(body(good)))
        val store = store(root)
        store.download()
        assertEquals(KokoroState.Installed, store.state.value)
    }

    @Test fun aFailedRequestKeepsThePartialCopyForLater() = runTest {
        val root = tmp.newFolder()
        File(root, "m.tar.bz2.part").writeBytes(good.copyOfRange(0, 5))
        server.enqueue(MockResponse().setResponseCode(503))
        val store = store(root)
        store.download()
        assertEquals(KokoroState.Failed("The download failed (HTTP 503).", 5), store.state.value)
        assertEquals(5, File(root, "m.tar.bz2.part").length())
    }

    @Test fun aDroppedConnectionGivesAPlainMessageAndKeepsTheBytes() = runTest {
        val root = tmp.newFolder()
        server.enqueue(MockResponse().setBody(body(good)).setSocketPolicy(okhttp3.mockwebserver.SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY))
        val store = store(root)
        store.download()
        assertEquals("The download stopped. Check your connection and try again.", (store.state.value as KokoroState.Failed).message)
    }

    @Test fun pathsLeavingTheInstallFolderAreRejected() = runTest {
        val root = tmp.newFolder()
        val evil = tarBz2("m/model.bin" to "x", "m/data/tokens.txt" to "x", "../../escaped.txt" to "x")
        server.enqueue(MockResponse().setBody(body(evil)))
        val store = store(root, model(evil))
        store.download()
        assertTrue(store.state.value is KokoroState.Failed)
        assertNull(store.modelDir())
        assertFalse(File(root.parentFile, "escaped.txt").exists())
    }

    @Test fun anArchiveMissingFilesIsNotInstalled() = runTest {
        val root = tmp.newFolder()
        val thin = tarBz2("m/model.bin" to "x")
        server.enqueue(MockResponse().setBody(body(thin)))
        val store = store(root, model(thin))
        store.download()
        assertEquals("The voice package is not valid.", (store.state.value as KokoroState.Failed).message)
        assertNull(store.modelDir())
    }

    @Test fun removeDeletesEverything() = runTest {
        val root = tmp.newFolder()
        server.enqueue(MockResponse().setBody(body(good)))
        val store = store(root)
        store.download()
        assertNotNull(store.modelDir())
        store.remove()
        assertEquals(KokoroState.NotInstalled(), store.state.value)
        assertTrue(root.list().orEmpty().isEmpty())
    }
}
