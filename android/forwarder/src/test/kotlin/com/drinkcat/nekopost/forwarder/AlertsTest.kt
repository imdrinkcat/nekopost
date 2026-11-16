package com.drinkcat.nekopost.forwarder

import android.Manifest
import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import androidx.test.core.app.ApplicationProvider
import com.drinkcat.nekopost.core.forwarder.PauseReason
import com.drinkcat.nekopost.forwarder.alert.AlertState
import com.drinkcat.nekopost.forwarder.alert.NotificationAlerts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class AlertsTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private val manager = app.getSystemService(NotificationManager::class.java)

    private fun titles(): List<String> =
        manager.activeNotifications.map { it.notification.extras.getCharSequence(Notification.EXTRA_TITLE).toString() }

    @Test
    fun showsAndClearsAlertsWithoutContent() {
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val alerts = NotificationAlerts(app)

        alerts.update(AlertState(paused = PauseReason.Unauthorized))
        assertEquals(listOf("转发已暂停：服务器拒绝了凭证"), titles())
        val n = manager.activeNotifications.single().notification
        assertEquals("forwarder_alerts", n.channelId)
        assertTrue(n.flags and Notification.FLAG_LOCAL_ONLY != 0)

        alerts.update(AlertState(stuck = 3))
        assertEquals(listOf("有 3 条消息未能转发"), titles())

        alerts.update(AlertState.None)
        assertTrue(manager.activeNotifications.isEmpty())
    }

    @Test
    fun nothingIsPostedWithoutPermission() {
        shadowOf(app).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        NotificationAlerts(app).update(AlertState(stuck = 2))
        assertTrue(manager.activeNotifications.isEmpty())
    }
}
