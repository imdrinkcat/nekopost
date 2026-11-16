package com.drinkcat.nekopost.core.forwarder

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class NotificationRuleTest {
    @Test
    fun modes() {
        assertTrue(NotificationRule.Default.accepts("任何", "内容"))
        val include = NotificationRule(NotificationRule.Mode.IncludeKeywords, listOf("出金", " Login "))
        assertTrue(include.accepts("出金のお知らせ", null))
        assertTrue(include.accepts(null, "new LOGIN detected"))
        assertFalse(include.accepts("ポイント", "貯まりました"))
        val exclude = NotificationRule(NotificationRule.Mode.ExcludeKeywords, listOf("广告"))
        assertFalse(exclude.accepts("【广告】双十一", null))
        assertTrue(exclude.accepts("快递", "已签收"))
        // No usable keywords: the mode has nothing to filter on.
        assertTrue(NotificationRule(NotificationRule.Mode.IncludeKeywords, listOf(" ")).accepts("x", "y"))
    }

    @Test
    fun backlogCountsAsBacklog() {
        val healthy = FakeForwarderData.enrolledState.copy(queue = QueueStats(), lastFailure = null)
        assertEquals(ForwarderHealth.Healthy, healthy.health())
        val held = healthy.copy(smsBacklog = SmsBacklog(51, null, Instant.EPOCH))
        assertEquals(ForwarderHealth.Backlog, held.health())

        val fake = FakeForwarderController(held)
        fake.resolveSmsBacklog(upload = false)
        assertNull(fake.state.value.smsBacklog)
        fake.setNotificationRule("jp.co.rakuten.bank", NotificationRule(includeTitle = false))
        assertFalse(fake.state.value.notificationRules.getValue("jp.co.rakuten.bank").includeTitle)
        fake.setNotificationRule("jp.co.rakuten.bank", null)
        assertTrue(fake.state.value.notificationRules.isEmpty())
    }
}
