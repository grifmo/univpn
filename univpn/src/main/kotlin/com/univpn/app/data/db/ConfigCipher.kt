package com.univpn.app.data.db

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Encrypts WireGuard configs (which contain private keys) before they reach the database.
 * The AES-256-GCM key lives in the Android Keystore and can't be read out of it.
 *
 * Stored format: "enc:v1:" + Base64(12-byte IV + ciphertext). Text without the prefix is a
 * config saved before encryption existed and is returned as is.
 */
object ConfigCipher {
    private const val TAG = "ConfigCipher"
    private const val KEYSTORE = "AndroidKeyStore"
    private const val KEY_ALIAS = "univpn_config_key"
    private const val PREFIX = "enc:v1:"
    private const val IV_BYTES = 12
    private const val TAG_BITS = 128

    fun isEncrypted(stored: String) = stored.startsWith(PREFIX)

    fun encrypt(plain: String): String {
        if (isEncrypted(plain)) return plain
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val out = cipher.iv + cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return PREFIX + Base64.encodeToString(out, Base64.NO_WRAP)
    }

    /** Returns the plain config, or "" if it can't be decrypted (e.g. the Keystore key is gone). */
    fun decrypt(stored: String): String {
        if (!isEncrypted(stored)) return stored
        return try {
            val data = Base64.decode(stored.substring(PREFIX.length), Base64.NO_WRAP)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, data, 0, IV_BYTES))
            String(cipher.doFinal(data, IV_BYTES, data.size - IV_BYTES), Charsets.UTF_8)
        } catch (e: Exception) {
            Log.e(TAG, "Could not decrypt a stored config: ${e.javaClass.simpleName}")
            ""
        }
    }

    @Synchronized
    private fun key(): SecretKey {
        val ks = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (ks.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE).run {
            init(
                KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build()
            )
            generateKey()
        }
    }
}
