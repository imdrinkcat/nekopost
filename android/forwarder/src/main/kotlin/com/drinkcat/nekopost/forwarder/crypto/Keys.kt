package com.drinkcat.nekopost.forwarder.crypto

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import android.util.Log
import com.google.crypto.tink.Aead
import com.google.crypto.tink.KeyTemplates
import com.google.crypto.tink.RegistryConfiguration
import com.google.crypto.tink.aead.AeadConfig
import com.google.crypto.tink.integration.android.AndroidKeysetManager
import java.security.KeyStore
import javax.crypto.KeyGenerator

/**
 * Source of the forwarder's AEAD: the Tink keyset `fwd` (AES-256-GCM), wrapped
 * by the non-exportable Android Keystore key `ks_fwd` (design §8.10). It needs
 * no user authentication, so SMS can be queued while the phone is locked.
 */
internal interface KeyManager {
    /** AEAD for the token, the device secret and outbox payloads; created on first use. */
    fun aead(): Aead

    /** Deletes the keyset and the Keystore key: everything they protected becomes unreadable. */
    fun destroy()
}

internal class AndroidKeyManager(private val context: Context) : KeyManager {
    @Volatile
    private var cached: Aead? = null

    override fun aead(): Aead = cached ?: synchronized(this) { cached ?: create().also { cached = it } }

    override fun destroy() = synchronized(this) {
        cached = null
        context.deleteSharedPreferences(KEYSET_PREFS)
        try {
            val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            if (keyStore.containsAlias(MASTER_ALIAS)) keyStore.deleteEntry(MASTER_ALIAS)
        } catch (e: Exception) {
            Log.w(TAG, "cannot delete the Keystore key: ${e.javaClass.simpleName}")
        }
    }

    private fun create(): Aead {
        AeadConfig.register()
        ensureMasterKey()
        val manager = AndroidKeysetManager.Builder()
            .withSharedPref(context, KEYSET_NAME, KEYSET_PREFS)
            .withKeyTemplate(KeyTemplates.get("AES256_GCM"))
            .withMasterKeyUri(MASTER_KEY_URI)
            .build()
        if (!manager.isUsingKeystore) Log.w(TAG, "Android Keystore unavailable; the keyset is not wrapped")
        return manager.keysetHandle.getPrimitive(RegistryConfiguration.get(), Aead::class.java)
    }

    /**
     * Creates `ks_fwd` ourselves so it lives in StrongBox when the device has
     * one; Tink then finds and uses the existing key.
     */
    private fun ensureMasterKey() {
        try {
            val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            if (keyStore.containsAlias(MASTER_ALIAS)) return
            // A keyset whose wrapping key is gone can never be read again.
            context.deleteSharedPreferences(KEYSET_PREFS)
            try {
                generateMasterKey(strongBox = true)
            } catch (e: StrongBoxUnavailableException) {
                generateMasterKey(strongBox = false)
            }
        } catch (e: Exception) {
            // Tink generates the key itself if this failed.
            Log.w(TAG, "cannot create the Keystore key: ${e.javaClass.simpleName}")
        }
    }

    private fun generateMasterKey(strongBox: Boolean) {
        val spec = KeyGenParameterSpec.Builder(
            MASTER_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setKeySize(256)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setIsStrongBoxBacked(strongBox)
            .build()
        KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE).apply { init(spec) }.generateKey()
    }

    private companion object {
        const val TAG = "NekoKeys"
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val MASTER_ALIAS = "nekopost_ks_fwd"
        const val MASTER_KEY_URI = "android-keystore://$MASTER_ALIAS"
        const val KEYSET_NAME = "fwd"
        const val KEYSET_PREFS = "nekopost_fwd_keyset"
    }
}
