package com.drinkcat.nekopost.inbox

import android.content.Context
import android.util.Base64
import com.google.crypto.tink.Aead
import com.google.crypto.tink.KeyTemplates
import com.google.crypto.tink.RegistryConfiguration
import com.google.crypto.tink.aead.AeadConfig
import com.google.crypto.tink.integration.android.AndroidKeysetManager

internal class DeviceVault(private val context: Context) {
    private val prefs = context.getSharedPreferences("nekopost_inbox", Context.MODE_PRIVATE)
    private var primitive: Aead? = null

    @Synchronized
    private fun aead(): Aead {
        primitive?.let { return it }
        AeadConfig.register()
        val manager = AndroidKeysetManager.Builder()
            .withSharedPref(context, "device", "nekopost_inbox_keyset")
            .withKeyTemplate(KeyTemplates.get("AES256_GCM"))
            .withMasterKeyUri("android-keystore://nekopost_inbox_main")
            .build()
        if (!manager.isUsingKeystore) {
            context.deleteSharedPreferences("nekopost_inbox_keyset")
            error("安全存储不可用")
        }
        return manager.keysetHandle.getPrimitive(RegistryConfiguration.get(), Aead::class.java).also {
            primitive = it
        }
    }

    @Synchronized
    fun read(key: String): String? = prefs.getString(key, null)?.let {
        aead().decrypt(Base64.decode(it, Base64.DEFAULT), key.toByteArray())
            .toString(Charsets.UTF_8)
    }

    @Synchronized
    fun write(key: String, value: String) {
        val encrypted = aead().encrypt(value.toByteArray(), key.toByteArray())
        check(prefs.edit().putString(key, Base64.encodeToString(encrypted, Base64.NO_WRAP)).commit())
    }

    @Synchronized
    fun remove(key: String) {
        prefs.edit().remove(key).commit()
    }

    /** Whether [key] is stored, without decrypting it (cheap enough for Application.onCreate). */
    @Synchronized
    fun contains(key: String): Boolean = prefs.contains(key)

    @Synchronized
    fun detailKeys(): Set<String> = prefs.all.keys.filter { it.startsWith("body:") }.toSet()

    @Synchronized
    fun clear() {
        primitive = null
        prefs.edit().clear().commit()
        context.deleteSharedPreferences("nekopost_inbox_keyset")
        java.security.KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            .deleteEntry("nekopost_inbox_main")
    }
}
