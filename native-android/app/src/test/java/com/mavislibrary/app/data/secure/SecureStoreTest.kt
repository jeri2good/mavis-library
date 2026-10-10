package com.mavislibrary.app.data.secure

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.spec.GCMParameterSpec

/** Same AES-GCM layout as KeystoreCipher, with a JVM key instead of AndroidKeyStore. */
private class JvmGcmCipher : SecretCipher {
    private val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    override fun encrypt(plain: ByteArray, associatedData: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key) }
        c.updateAAD(associatedData)
        return c.iv + c.doFinal(plain)
    }
    override fun decrypt(sealed: ByteArray, associatedData: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, sealed, 0, 12)) }
        c.updateAAD(associatedData)
        return c.doFinal(sealed, 12, sealed.size - 12)
    }
}

private object JvmBase64 : Codec {
    override fun encode(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)
    override fun decode(text: String): ByteArray = Base64.getDecoder().decode(text)
}

private object RefusingCipher : SecretCipher {
    override fun encrypt(plain: ByteArray, associatedData: ByteArray): ByteArray = throw IllegalStateException("keystore unavailable")
    override fun decrypt(sealed: ByteArray, associatedData: ByteArray): ByteArray = throw IllegalStateException("keystore unavailable")
}

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class SecureStoreTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun TestScope.dataStore() = PreferenceDataStoreFactory.create(scope = backgroundScope) {
        tmp.newFile("secure.preferences_pb").also { it.delete() }
    }

    @Test
    fun writeReadClearRoundTripAndNoPlaintextOnDisk() = runTest(UnconfinedTestDispatcher()) {
        val ds = dataStore()
        val store = SecureStore(ds, JvmGcmCipher(), JvmBase64)
        assertFalse(store.has(Secret.OWNER_ACCESS_CODE).first())
        assertTrue(store.write(Secret.OWNER_ACCESS_CODE, "hunter2-owner"))
        assertTrue(store.has(Secret.OWNER_ACCESS_CODE).first())
        assertEquals("hunter2-owner", store.read(Secret.OWNER_ACCESS_CODE))
        val raw = ds.data.first().asMap().values.joinToString()
        assertFalse(raw.contains("hunter2"))
        store.clear(Secret.OWNER_ACCESS_CODE)
        assertNull(store.read(Secret.OWNER_ACCESS_CODE))
    }

    @Test
    fun ciphertextIsBoundToItsEntry() = runTest(UnconfinedTestDispatcher()) {
        val ds = dataStore()
        val cipher = JvmGcmCipher()
        val store = SecureStore(ds, cipher, JvmBase64)
        store.write(Secret.OWNER_ACCESS_CODE, "code")
        val sealed = ds.data.first().asMap().values.single() as String
        assertTrue(runCatching { cipher.decrypt(JvmBase64.decode(sealed), Secret.ACCOUNT_TOKEN.id.toByteArray()) }.isFailure)
    }

    @Test
    fun refusedEncryptionStoresNothing() = runTest(UnconfinedTestDispatcher()) {
        val ds = dataStore()
        val store = SecureStore(ds, RefusingCipher, JvmBase64)
        assertFalse(store.write(Secret.ACCOUNT_TOKEN, "token"))
        assertTrue(ds.data.first().asMap().isEmpty())
    }
}
