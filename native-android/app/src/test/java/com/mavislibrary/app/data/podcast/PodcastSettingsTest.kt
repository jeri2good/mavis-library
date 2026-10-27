package com.mavislibrary.app.data.podcast

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.mavislibrary.app.data.secure.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.spec.GCMParameterSpec
import java.util.Base64

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class PodcastSettingsTest {
    @get:Rule val tmp = TemporaryFolder()
    private val cipher = object : SecretCipher {
        val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        override fun encrypt(plain: ByteArray, associatedData: ByteArray): ByteArray {
            val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key); updateAAD(associatedData) }
            return c.iv + c.doFinal(plain)
        }
        override fun decrypt(sealed: ByteArray, associatedData: ByteArray): ByteArray = Cipher.getInstance("AES/GCM/NoPadding").run {
            init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, sealed, 0, 12)); updateAAD(associatedData)
            doFinal(sealed, 12, sealed.size - 12)
        }
    }
    private val codec = object : Codec {
        override fun encode(bytes: ByteArray) = Base64.getEncoder().encodeToString(bytes)
        override fun decode(text: String) = Base64.getDecoder().decode(text)
    }
    private fun TestScope.store(name: String) = PreferenceDataStoreFactory.create(scope = backgroundScope) { tmp.root.resolve("$name.preferences_pb") }
    @Test fun subscriptionCleanupDeletesOnlyExpiredAccountFilesAndKeepsStreamMetadata() = runTest(UnconfinedTestDispatcher()) {
        val settings = PodcastSettings(store("settings"), SecureStore(store("secure"), cipher, codec))
        val files = com.mavislibrary.app.audio.AudioFileStore(tmp.root, okhttp3.OkHttpClient())
        val api = PodcastApi("https://studio.example.org", execute = { fakeSignedAudio(it) ?: fakeResponse(it, "fake-mp3", type = "audio/mpeg") }, token = { "fixture" })
        val now = java.time.Instant.parse("2026-10-27T12:00:00Z")
        for ((id, account, created) in listOf(Triple("old", "alice", "2026-10-19T12:00:00Z"),
            Triple("boundary", "alice", "2026-10-20T12:00:00Z"), Triple("other", "bob", "2026-10-19T12:00:00Z"))) {
            val file = files.downloadPodcast(api, id)
            settings.save(SavedPodcast(podcastFixtures()[4].copy(id = id, createdAt = created), api.address, file.absolutePath, accountId = account))
        }
        assertEquals(1, files.cleanupSubscriptionDownloads(settings, api.address, "alice", 7, now))
        assertNull(files.podcastLocal(api.address, "old"))
        assertNotNull(files.podcastLocal(api.address, "boundary")); assertNotNull(files.podcastLocal(api.address, "other"))
        val library = settings.library.first()
        assertEquals(3, library.size); assertNull(library.first { it.job.id == "old" }.localFile)
    }
    @Test fun blankDefaultsAndEncryptedKeySurviveNewWrapper() = runTest(UnconfinedTestDispatcher()) {
        val ds = store("settings"); val secure = store("secure"); val secrets = SecureStore(secure, cipher, codec)
        val settings = PodcastSettings(ds, secrets)
        assertEquals(PodcastPreferences(), settings.preferences.first())
        assertTrue(settings.configure("http://192.168.1.71:8140/", "fixture-secret"))
        settings.setDefaults("critique", "long", "local"); settings.setCleanupDays(30)
        val reopened = PodcastSettings(ds, secrets)
        assertEquals("fixture-secret", reopened.tokenFor("http://192.168.1.71:8140"))
        assertFalse(secure.data.first().asMap().values.joinToString().contains("fixture-secret"))
        assertFalse(ds.data.first().asMap().values.joinToString().contains("fixture-secret"))
        assertEquals(PodcastPreferences("http://192.168.1.71:8140", "critique", "long", "local", 30), reopened.preferences.first())
    }
    @Test fun cleanupPolicyDoesNotResurrectExpiredServerItems() = runTest(UnconfinedTestDispatcher()) {
        val settings = PodcastSettings(store("settings"), SecureStore(store("secure"), cipher, codec))
        settings.setCleanupDays(30)
        settings.save(SavedPodcast(podcastFixtures()[4].copy(createdAt = "2000-01-01T00:00:00Z"), "studio"))
        assertTrue(settings.library.first().isEmpty())
        settings.save(SavedPodcast(podcastFixtures().first().copy(createdAt = "2000-01-01T00:00:00Z"), "studio"))
        assertEquals("queued", settings.library.first().single().job.status)
    }
    @Test fun changingStudioClearsTokenAndRefusesOldOrigin() = runTest(UnconfinedTestDispatcher()) {
        val settings = PodcastSettings(store("settings"), SecureStore(store("secure"), cipher, codec))
        settings.configure("https://first.example.org", "fixture-key")
        assertNull(settings.tokenFor("https://second.example.org"))
        settings.configure("https://second.example.org")
        assertNull(settings.tokenFor("https://first.example.org")); assertNull(settings.tokenFor("https://second.example.org"))
        settings.configure("", ""); assertEquals("", settings.address())
    }
    @Test fun invalidAddressOrKeyDoesNotOverwriteConfiguration() = runTest(UnconfinedTestDispatcher()) {
        val settings = PodcastSettings(store("settings"), SecureStore(store("secure"), cipher, codec))
        settings.configure("https://studio.example.org", "fixture")
        assertTrue(runCatching { settings.configure("http://public.example.org", "other") }.isFailure)
        assertTrue(runCatching { settings.configure("https://other.example.org", "bad\nheader") }.isFailure)
        assertEquals("https://studio.example.org", settings.address()); assertEquals("fixture", settings.tokenFor(settings.address()))
        assertTrue(runCatching { settings.setCleanupDays(-1) }.isFailure)
        assertTrue(runCatching { settings.setDefaults("invalid", "short", "auto") }.isFailure)
    }
    @Test fun cachedJobsKeepOfflineFileAndRetryRequestAcrossPollAndListUpdates() = runTest(UnconfinedTestDispatcher()) {
        val settings = PodcastSettings(store("settings"), SecureStore(store("secure"), cipher, codec))
        val saved = SavedPodcast(podcastFixtures().first(), "studio", "private.mp3", PodcastRequest("topic", "Topic", query = "Space"))
        settings.save(saved); settings.save(SavedPodcast(podcastFixtures()[4], "studio"))
        val reopened = PodcastSettings(store = store("other"), secrets = SecureStore(store("secure-other"), cipher, codec))
        assertTrue(reopened.library.first().isEmpty())
        val persisted = settings.library.first().single()
        assertEquals("private.mp3", persisted.localFile); assertEquals("Space", persisted.request!!.query)
        assertNotNull(persisted.job.transcript)
        settings.remove("job1", "different-studio"); assertEquals(1, settings.library.first().size)
        settings.remove("job1", "studio"); assertTrue(settings.library.first().isEmpty())
    }
}
