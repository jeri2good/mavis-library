package com.mavislibrary.app.data.secure

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Encrypts small secrets. The production implementation keeps its key inside Android Keystore. */
interface SecretCipher {
    fun encrypt(plain: ByteArray, associatedData: ByteArray): ByteArray
    fun decrypt(sealed: ByteArray, associatedData: ByteArray): ByteArray
}

/** AES-256-GCM with a non-exportable key held by AndroidKeyStore. Output = 12-byte IV ‖ ciphertext+tag. */
class KeystoreCipher(private val alias: String = "mavis_secure_store_v1") : SecretCipher {
    private fun key(): SecretKey {
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (ks.getKey(alias, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        gen.init(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
                .build(),
        )
        return gen.generateKey()
    }

    override fun encrypt(plain: ByteArray, associatedData: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, key()) }
        cipher.updateAAD(associatedData)
        return cipher.iv + cipher.doFinal(plain)
    }

    override fun decrypt(sealed: ByteArray, associatedData: ByteArray): ByteArray {
        require(sealed.size > IV_BYTES) { "sealed value too short" }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, sealed, 0, IV_BYTES))
        cipher.updateAAD(associatedData)
        return cipher.doFinal(sealed, IV_BYTES, sealed.size - IV_BYTES)
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
    }
}

/** Base64 seam so the store is testable on the JVM. */
interface Codec {
    fun encode(bytes: ByteArray): String
    fun decode(text: String): ByteArray
}

object AndroidBase64 : Codec {
    override fun encode(bytes: ByteArray): String = Base64.encodeToString(bytes, Base64.NO_WRAP)
    override fun decode(text: String): ByteArray = Base64.decode(text, Base64.NO_WRAP)
}

enum class Secret(val id: String) {
    OWNER_ACCESS_CODE("owner_access_code"),
    ACCOUNT_TOKEN("account_token"),
}

/**
 * Holds secrets as AES-GCM ciphertext only (never plaintext on disk). Each value is bound to its
 * name through GCM associated data, so ciphertexts cannot be swapped between entries.
 * Nothing here logs values; failures surface as null/false, never with the secret in the message.
 */
class SecureStore(
    private val store: DataStore<Preferences>,
    private val cipher: SecretCipher,
    private val codec: Codec = AndroidBase64,
) {
    fun has(secret: Secret): Flow<Boolean> =
        store.data.map { it[keyOf(secret)] != null }.catch { emit(false) }

    suspend fun read(secret: Secret): String? {
        val sealed = runCatching { store.data.first()[keyOf(secret)] }.getOrNull() ?: return null
        return runCatching { cipher.decrypt(codec.decode(sealed), secret.id.toByteArray()).decodeToString() }.getOrNull()
    }

    /** Returns false when the device's secure hardware refused the operation. */
    suspend fun write(secret: Secret, value: String): Boolean {
        val sealed = runCatching { codec.encode(cipher.encrypt(value.toByteArray(), secret.id.toByteArray())) }.getOrNull()
            ?: return false
        store.edit { it[keyOf(secret)] = sealed }
        return true
    }

    suspend fun clear(secret: Secret) {
        store.edit { it.remove(keyOf(secret)) }
    }

    private fun keyOf(secret: Secret) = stringPreferencesKey("sealed_${secret.id}")
}
