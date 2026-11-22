package id.kamui.runsense.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** AES-256-GCM credential vault backed by a non-exportable Android Keystore key. */
internal class ApiKeyVault(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("runsense_native_v1", Context.MODE_PRIVATE)
    private val encryptedKey = "runsense_key_enc_v1"
    private val legacyKey = "runsense_key"
    private val alias = "runsense_intervals_api_key_v1"

    @Synchronized fun read(): String? {
        val encoded = prefs.getString(encryptedKey, null)
        if (!encoded.isNullOrBlank()) {
            return runCatching { decrypt(encoded) }.getOrNull()
        }
        // One-time migration from the former plaintext preference.
        val legacy = prefs.getString(legacyKey, null)?.takeIf { it.isNotBlank() } ?: return null
        return runCatching {
            prefs.edit().putString(encryptedKey, encrypt(legacy)).remove(legacyKey).commit()
            legacy
        }.getOrElse { null }
    }

    @Synchronized fun write(value: String) {
        val encrypted = encrypt(value)
        check(prefs.edit().putString(encryptedKey, encrypted).remove(legacyKey).commit()) {
            "Kunci API tidak berhasil disimpan secara aman."
        }
    }

    @Synchronized fun remove() {
        prefs.edit().remove(encryptedKey).remove(legacyKey).apply()
    }

    private fun encrypt(value: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val payload = cipher.iv + cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(payload, Base64.NO_WRAP)
    }

    private fun decrypt(encoded: String): String {
        val payload = Base64.decode(encoded, Base64.NO_WRAP)
        require(payload.size > 12) { "Data kunci API rusak." }
        val iv = payload.copyOfRange(0, 12)
        val ciphertext = payload.copyOfRange(12, payload.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, iv))
        return String(cipher.doFinal(ciphertext), Charsets.UTF_8)
    }

    private fun secretKey(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(
                alias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return generator.generateKey()
    }
}
