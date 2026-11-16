package com.drinkcat.nekopost.system

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.service.notification.NotificationListenerService
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.drinkcat.nekopost.core.forwarder.PermissionStatus
import com.drinkcat.nekopost.ui.setup.DeviceTraits

/*
 * Everything the UI needs to know about (and open in) the system settings.
 * Detection runs on every onResume (design §8.6) and is reported to the
 * forwarder through ForwarderController.reportPermissions.
 */

fun Context.readPermissionStatus(): PermissionStatus {
    fun granted(p: String) = ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED
    val power = getSystemService(PowerManager::class.java)
    return PermissionStatus(
        receiveSms = granted(Manifest.permission.RECEIVE_SMS),
        readSms = granted(Manifest.permission.READ_SMS),
        notificationListener = NotificationManagerCompat.getEnabledListenerPackages(this).contains(packageName),
        postNotifications = if (Build.VERSION.SDK_INT >= 33) {
            granted(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            NotificationManagerCompat.from(this).areNotificationsEnabled()
        },
        ignoringBatteryOptimizations = power?.isIgnoringBatteryOptimizations(packageName) == true,
    )
}

fun Context.readDeviceTraits(): DeviceTraits = DeviceTraits(
    sdkInt = Build.VERSION.SDK_INT,
    isSamsung = Build.MANUFACTURER.equals("samsung", ignoreCase = true),
    isFoldable = packageManager.hasSystemFeature(PackageManager.FEATURE_SENSOR_HINGE_ANGLE),
)

/** Intents into system settings. Each falls back to a broader screen if the precise one is missing. */
object SystemIntents {
    fun appDetails(context: Context) =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))

    /** Our own notification listener toggle (API 30+); the general list if we cannot find the component. */
    fun notificationAccess(context: Context): Intent {
        val component = notificationListenerComponent(context)
        return if (component != null) {
            Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
                .putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME, component.flattenToString())
        } else {
            Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
        }
    }

    fun notificationAccessFallback() = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)

    fun appNotificationSettings(context: Context) =
        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)

    /** The system "allow ignoring battery optimizations?" dialog. */
    fun requestIgnoreBatteryOptimizations(context: Context) =
        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}"))

    fun batteryOptimizationList() = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)

    fun displaySettings() = Intent(Settings.ACTION_DISPLAY_SETTINGS)

    /** Finds the NotificationListenerService the forwarder module declares, whatever its class name. */
    private fun notificationListenerComponent(context: Context): ComponentName? {
        val intent = Intent(NotificationListenerService.SERVICE_INTERFACE).setPackage(context.packageName)
        return context.packageManager.queryIntentServices(intent, 0).firstOrNull()?.serviceInfo?.let {
            ComponentName(it.packageName, it.name)
        }
    }
}

/** Starts the first intent that resolves; returns false if none did. */
fun Context.startFirst(vararg intents: Intent): Boolean {
    for (intent in intents) {
        try {
            startActivity(intent)
            return true
        } catch (e: ActivityNotFoundException) {
            continue
        } catch (e: SecurityException) {
            continue
        }
    }
    return false
}
