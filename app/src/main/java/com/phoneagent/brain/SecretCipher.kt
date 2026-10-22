package com.phoneagent.brain

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.phoneagent.util.LogLevel
import com.phoneagent.util.Logger
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Encrypts API keys with a key that never leaves the Android Keystore.
 *
 * The point of this is that a key is not readable from a backup or from a rooted phone's
 * app-data dump: the wrapping key is held by the hardware-backed keystore and cannot be
 * exported. The stored value is `base64(iv) : base64(ciphertext)`.
 *
 * If the keystore key is lost, which happens after a device restore or a "clear credentials",
 * decryption fails. That is handled by treating the value as absent rather than crashing, so
 * the user is asked for the key again instead of the app refusing to start.
 */
interface SecretCipher {
    fun encrypt(plainText: String): String
    fun decrypt(stored: String): String?
}

class KeystoreSecretCipher(
    private val logger: Logger,
    private val alias: String = KEY_ALIAS,
) : SecretCipher {

    override fun encrypt(plainText: String): String {
        if (plainText.isEmpty()) return ""
        return runCatching {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, secretKey())
            val ciphertext = cipher.doFinal(plainText.toByteArray(Charsets.UTF_8))
            // The IV must be stored: GCM generates a fresh one per encryption, and without it
            // the value cannot be read back.
            encode(cipher.iv) + SEPARATOR + encode(ciphertext)
        }.getOrElse { error ->
            logger.log(LogLevel.ERROR, TAG, "Could not encrypt: ${error.message}")
            throw IllegalStateException("The key could not be stored securely.", error)
        }
    }

    override fun decrypt(stored: String): String? {
        if (stored.isEmpty()) return ""
        val parts = stored.split(SEPARATOR)
        if (parts.size != 2) return null

        return runCatching {
            val iv = decode(parts[0])
            val ciphertext = decode(parts[1])
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(TAG_LENGTH_BITS, iv))
            String(cipher.doFinal(ciphertext), Charsets.UTF_8)
        }.getOrElse { error ->
            // A restore from another device invalidates the keystore key. The honest response
            // is to forget the value and ask again.
            logger.log(LogLevel.WARN, TAG, "Could not decrypt a stored key: ${error.message}")
            null
        }
    }

    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getEntry(alias, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                alias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                // Deliberately not requiring user authentication: the agent runs in the
                // background, and a prompt on every step would make it unusable.
                .setUserAuthenticationRequired(false)
                .build(),
        )
        return generator.generateKey()
    }

    private fun encode(bytes: ByteArray): String = Base64.encodeToString(bytes, Base64.NO_WRAP)

    private fun decode(text: String): ByteArray = Base64.decode(text, Base64.NO_WRAP)

    companion object {
        const val TAG = "SecretCipher"
        const val KEY_ALIAS = "phoneagent_provider_keys"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val TAG_LENGTH_BITS = 128
        private const val SEPARATOR = ":"
    }
}

/**
 * Reversible-but-not-plaintext cipher used in JVM tests, where the Android Keystore does not
 * exist. It is not encryption; it only proves the repository stores what the cipher returned
 * rather than the raw key.
 */
class ReversingSecretCipher : SecretCipher {
    override fun encrypt(plainText: String): String =
        "enc:" + plainText.reversed()

    override fun decrypt(stored: String): String? =
        stored.removePrefix("enc:").takeIf { stored.startsWith("enc:") }?.reversed()
}
