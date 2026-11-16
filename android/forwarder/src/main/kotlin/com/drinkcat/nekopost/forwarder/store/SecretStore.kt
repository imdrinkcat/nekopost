package com.drinkcat.nekopost.forwarder.store

import android.annotation.SuppressLint
import android.content.SharedPreferences
import com.drinkcat.nekopost.forwarder.crypto.KeyManager
import com.drinkcat.nekopost.forwarder.crypto.KeystoreUnavailableException
import java.security.GeneralSecurityException
import java.security.SecureRandom
import java.util.Base64

/**
 * The source token (`mgs_…`) and the 32-byte device secret, each encrypted
 * with the forwarder AEAD (AAD = the entry name). Nothing here is ever logged.
 */
internal class SecretStore(
    private val prefs: SharedPreferences,
    private val keys: KeyManager,
    private val random: SecureRandom = SecureRandom(),
) {
    @Synchronized
    fun token(): String? = read(TOKEN)?.toString(Charsets.UTF_8)

    @Synchronized
    fun setToken(token: String) = write(TOKEN, token.toByteArray(Charsets.UTF_8))

    /** The HMAC key for SMS idempotency keys; generated on first use. */
    @Synchronized
    fun deviceSecret(): ByteArray =
        read(DEVICE_SECRET)?.takeIf { it.size == SECRET_BYTES }
            ?: ByteArray(SECRET_BYTES).also {
                random.nextBytes(it)
                write(DEVICE_SECRET, it)
            }

    @SuppressLint("ApplySharedPref") // must be on disk before we report success
    @Synchronized
    fun clear() {
        prefs.edit().clear().commit()
    }

    private fun read(name: String): ByteArray? {
        val stored = prefs.getString(name, null) ?: return null
        return try {
            keys.aead().decrypt(Base64.getDecoder().decode(stored), aad(name))
        } catch (e: KeystoreUnavailableException) {
            // Temporary: the stored value is fine, the key cannot be loaded right now.
            throw e
        } catch (e: GeneralSecurityException) {
            null
        } catch (e: IllegalArgumentException) {
            null
        }
    }

    @SuppressLint("ApplySharedPref") // must be on disk before we report success
    private fun write(name: String, value: ByteArray) {
        val sealed = keys.aead().encrypt(value, aad(name))
        check(prefs.edit().putString(name, Base64.getEncoder().encodeToString(sealed)).commit()) {
            "secret store write failed"
        }
    }

    private fun aad(name: String): ByteArray = "nekopost/secret/$name".toByteArray(Charsets.UTF_8)

    companion object {
        const val PREFS_NAME = "nekopost_fwd_secrets"
        private const val TOKEN = "token"
        private const val DEVICE_SECRET = "device_secret"
        private const val SECRET_BYTES = 32
    }
}
