package com.drinkcat.nekopost.forwarder.notify

import android.content.Context
import android.provider.Telephony
import com.drinkcat.nekopost.core.forwarder.NotificationAppPolicy
import java.util.concurrent.ConcurrentHashMap

/** Facts about the app that posted a notification; faked in tests. */
internal interface AppTraits {
    /** The default SMS app or a well-known SMS/RCS client: its notifications repeat incoming SMS. */
    fun isSmsApp(packageName: String): Boolean

    /** A Telegram client that is not in [NotificationAppPolicy.blockedPackages] (an unlisted fork). */
    fun isTelegramClient(packageName: String): Boolean
}

internal class PackageManagerAppTraits(private val context: Context) : AppTraits {
    private val telegram = ConcurrentHashMap<String, Boolean>()

    override fun isSmsApp(packageName: String): Boolean =
        packageName in KNOWN_SMS_APPS || packageName == defaultSmsPackage()

    override fun isTelegramClient(packageName: String): Boolean = telegram.getOrPut(packageName) {
        val launcher = try {
            context.packageManager.getLaunchIntentForPackage(packageName)?.component?.className
        } catch (e: RuntimeException) {
            null
        }
        NotificationAppPolicy.isTelegramComponent(launcher)
    }

    private fun defaultSmsPackage(): String? = try {
        Telephony.Sms.getDefaultSmsPackage(context)
    } catch (e: RuntimeException) {
        null
    }

    private companion object {
        val KNOWN_SMS_APPS = setOf(
            "com.samsung.android.messaging",
            "com.google.android.apps.messaging",
            "com.android.mms",
            "com.android.messaging",
        )
    }
}
