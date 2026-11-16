package com.drinkcat.nekopost.core.forwarder

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.time.Duration

class ForwarderHealthTest {
    private val healthy = FakeForwarderData.enrolledState.copy(queue = QueueStats(), lastFailure = null)

    @Test
    fun health() {
        assertEquals(ForwarderHealth.NotEnrolled, ForwarderState.Initial.health())
        assertEquals(ForwarderHealth.Healthy, healthy.health())
        assertEquals(ForwarderHealth.Backlog, healthy.copy(queue = QueueStats(pending = 3)).health())
        assertEquals(ForwarderHealth.Paused, healthy.copy(paused = PauseReason.Unauthorized).health())
        assertEquals(
            ForwarderHealth.NeedsPermission,
            healthy.copy(permissions = healthy.permissions.copy(receiveSms = false)).health(),
        )
        assertEquals(
            ForwarderHealth.Idle,
            healthy.copy(smsEnabled = false, notificationsEnabled = false).health(),
        )
    }

    @Test
    fun otpVerdict() {
        assertEquals(OtpVerdict.NoData, OtpHealth.Empty.verdict)
        assertEquals(OtpVerdict.NoDelay, FakeForwarderData.otpHealthy.verdict)
        assertEquals(OtpVerdict.DelayDetected, FakeForwarderData.otpDelayed.verdict)
        assertEquals(Duration.ofMinutes(181), FakeForwarderData.otpDelayed.maxDelay)
    }

    @Test
    fun telegramAppsAreNeverSelectable() {
        assertFalse(NotificationAppPolicy.isSelectable("org.telegram.messenger"))
        assertFalse(NotificationAppPolicy.isSelectable("com.drinkcat.nekopost"))
        val fake = FakeForwarderController()
        fake.setNotificationApps(setOf("org.telegram.messenger", "jp.co.rakuten.bank"))
        assertEquals(setOf("jp.co.rakuten.bank"), fake.state.value.notificationApps)
    }
}
