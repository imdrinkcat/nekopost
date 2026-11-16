package com.drinkcat.nekopost.forwarder.alert

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.drinkcat.nekopost.core.forwarder.PauseReason
import com.drinkcat.nekopost.forwarder.R

/** What the forwarder's own notifications should show right now. */
internal data class AlertState(
    /** Forwarding is paused (401 / 403): the queue waits for the user. */
    val paused: PauseReason? = null,
    /** Pending items while the oldest has waited longer than 30 minutes; 0 = no alert. */
    val stuck: Int = 0,
) {
    companion object {
        val None = AlertState()
    }
}

/** Posts or clears the forwarder's alerts (design §8.7); faked in tests. */
internal interface Alerts {
    /** Idempotent: shows exactly what [state] says and cancels the rest. */
    fun update(state: AlertState)
}

/**
 * Local notifications on the "转发告警" channel. They carry no message content
 * (counts and reasons only), stay on the phone (setLocalOnly) and open the app.
 * Without POST_NOTIFICATIONS nothing is shown; the app still shows the same
 * state on its home screen.
 */
internal class NotificationAlerts(private val context: Context) : Alerts {
    private val manager = NotificationManagerCompat.from(context)
    private var shown: AlertState? = null

    @Synchronized
    override fun update(state: AlertState) {
        if (state == shown) return
        if (state.paused == null) manager.cancel(ID_PAUSED)
        if (state.stuck == 0) manager.cancel(ID_STUCK)
        if (state.paused == null && state.stuck == 0) {
            shown = state
            return
        }
        if (!canPost()) {
            // Try again on the next update, the permission may be granted by then.
            shown = null
            return
        }
        ensureChannel()
        if (state.paused != null) {
            val (title, text) = when (state.paused) {
                PauseReason.Unauthorized -> R.string.fwd_alert_paused_unauthorized_title to R.string.fwd_alert_paused_unauthorized_text
                PauseReason.SourceDisabled -> R.string.fwd_alert_paused_disabled_title to R.string.fwd_alert_paused_disabled_text
            }
            post(ID_PAUSED, context.getString(title), context.getString(text), NotificationCompat.PRIORITY_HIGH)
        }
        if (state.stuck > 0) {
            post(
                ID_STUCK,
                context.getString(R.string.fwd_alert_stuck_title, state.stuck),
                context.getString(R.string.fwd_alert_stuck_text),
                NotificationCompat.PRIORITY_LOW,
            )
        }
        shown = state
    }

    private fun canPost(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun ensureChannel() {
        val channel = NotificationChannelCompat.Builder(CHANNEL, NotificationManagerCompat.IMPORTANCE_DEFAULT)
            .setName(context.getString(R.string.fwd_alert_channel))
            .setDescription(context.getString(R.string.fwd_alert_channel_description))
            .build()
        manager.createNotificationChannel(channel)
    }

    private fun post(id: Int, title: String, text: String, priority: Int) {
        val open = context.packageManager.getLaunchIntentForPackage(context.packageName)?.let {
            PendingIntent.getActivity(context, id, it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.fwd_ic_alert)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(priority)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setOnlyAlertOnce(true)
            .setLocalOnly(true)
            .setAutoCancel(true)
            .setContentIntent(open)
            .build()
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
        ) {
            return
        }
        try {
            manager.notify(id, notification)
        } catch (e: SecurityException) {
            // Permission revoked in between; the home screen shows the same state.
        }
    }

    private companion object {
        const val CHANNEL = "forwarder_alerts"
        const val ID_PAUSED = 1001
        const val ID_STUCK = 1002
    }
}
