package com.drinkcat.nekopost.forwarder.notify

import android.content.ComponentName
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import com.drinkcat.nekopost.core.forwarder.NotificationAppPolicy
import com.drinkcat.nekopost.forwarder.ForwarderGraph
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Bound by the system once the user grants notification access (only the
 * system holds BIND_NOTIFICATION_LISTENER_SERVICE). Reads the notification on
 * the binder thread, hands plain data to [NotificationIngest].
 */
class NekoNotificationListener : NotificationListenerService() {
    private val placeholders by lazy { RedactionPlaceholders.fromSystem() }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        val posted = sbn ?: return
        val pkg = posted.packageName ?: return
        if (pkg == packageName || !NotificationAppPolicy.isSelectable(pkg)) return
        val extraction = try {
            NotificationExtractor.extract(pkg, posted.key, posted.postTime, posted.notification, placeholders)
        } catch (e: RuntimeException) {
            Log.w(TAG, "cannot read notification: ${e.javaClass.simpleName}")
            return
        }
        val captured = (extraction as? Extraction.Captured)?.notification ?: return
        val graph = ForwarderGraph.get(this)
        graph.scope.launch {
            try {
                val decision = graph.notifications.onPosted(captured)
                if (decision is NotificationDecision.Coalesced && decision.first) {
                    delay((decision.flushAt - graph.clock.millis()).coerceAtLeast(0))
                    graph.notifications.flushDue()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "notification capture failed: ${e.javaClass.simpleName}")
            }
        }
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        // Ask the system to bind us again (design §8.5).
        NotificationListenerService.requestRebind(ComponentName(this, NekoNotificationListener::class.java))
    }

    private companion object {
        const val TAG = "NekoNotify"
    }
}
