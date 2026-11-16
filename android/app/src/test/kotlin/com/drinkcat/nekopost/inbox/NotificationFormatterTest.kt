package com.drinkcat.nekopost.inbox

import com.drinkcat.nekopost.inbox.notify.NotificationFormatter
import com.drinkcat.nekopost.inbox.notify.NotificationTexts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/** What a message notification says, and above all what the lock screen never says. */
class NotificationFormatterTest {
    private val texts = NotificationTexts(
        appTitle = "猫邮局",
        publicMessage = "1 条新消息",
        publicCode = "1 条验证码",
        publicSummary = "%1\$d 条新消息",
        codeLine = "验证码 %1\$s",
        expired = "正文已过期",
        newMessage = "有新消息",
        copied = "已复制 · 60 秒后清除",
    )
    private val future = Instant.parse("2030-01-01T00:00:00Z")

    private val otp = InboxMessage(
        id = "m_01JB7QAB12CD34EF56GH78JK9M",
        sender = "95555",
        title = "******",
        preview = "【招商银行】您的验证码为******，5分钟内有效。",
        sourceId = "fold8",
        sourceName = "Fold8 主力机",
        kind = MessageKind.Sms,
        category = "验证码",
        receivedAt = Instant.parse("2026-11-16T00:41:00Z"),
        expiresAt = future,
        hasCode = true,
        body = "【招商银行】您的验证码为482913，5分钟内有效。",
        otp = "482913",
    )

    private val chat = otp.copy(
        id = "m_01JB7QAB12CD34EF56GH78JK9N",
        sender = "微信 · 张三",
        preview = "今晚一起吃饭吗？",
        body = "今晚一起吃饭吗？\n七点，老地方。",
        kind = MessageKind.Notification,
        category = "通知",
        hasCode = false,
        otp = null,
    )

    @Test
    fun verificationCodeIsUpFrontOnTheCodesChannel() {
        val spec = NotificationFormatter.format(otp, showContent = true, texts = texts)
        assertEquals(NotificationFormatter.CHANNEL_CODES, spec.channel)
        assertEquals("95555", spec.title)
        assertEquals("验证码 482913", spec.text)
        assertTrue(spec.bigText.contains("482913"))
        assertTrue(spec.copyAction)
        assertEquals("Fold8 主力机", spec.subText)
        assertEquals(otp.receivedAt!!.toEpochMilli(), spec.whenMillis)
    }

    @Test
    fun ordinaryMessageLooksLikeAChat() {
        val spec = NotificationFormatter.format(chat, showContent = true, texts = texts)
        assertEquals(NotificationFormatter.CHANNEL_MESSAGES, spec.channel)
        assertEquals("微信 · 张三", spec.title)
        assertEquals("今晚一起吃饭吗？", spec.text)
        assertEquals("今晚一起吃饭吗？\n七点，老地方。", spec.bigText)
        assertFalse(spec.copyAction)
    }

    @Test
    fun lowPriorityIsQuiet() {
        val spec = NotificationFormatter.format(chat.copy(priority = MessagePriority.Low), showContent = true, texts = texts)
        assertEquals(NotificationFormatter.CHANNEL_QUIET, spec.channel)
        // A code always alerts, whatever its priority.
        assertEquals(NotificationFormatter.CHANNEL_CODES, NotificationFormatter.channelFor(otp.copy(priority = MessagePriority.Low)))
    }

    @Test
    fun publicVersionNeverRevealsSenderBodyOrCode() {
        for (item in listOf(otp, chat)) {
            for (show in listOf(true, false)) {
                val spec = NotificationFormatter.format(item, show, texts)
                val public = spec.publicTitle + "\n" + spec.publicText
                assertFalse(public.contains("482913"))
                assertFalse(public.contains("95555"))
                assertFalse(public.contains("张三"))
                assertFalse(public.contains("吃饭"))
                assertEquals("猫邮局", spec.publicTitle)
            }
        }
        assertEquals("1 条验证码", NotificationFormatter.format(otp, true, texts).publicText)
        assertEquals("1 条新消息", NotificationFormatter.format(chat, true, texts).publicText)
    }

    @Test
    fun contentHiddenFallsBackToSourceAndCategory() {
        val spec = NotificationFormatter.format(otp, showContent = false, texts = texts)
        assertEquals("Fold8 主力机", spec.title)
        assertEquals("Fold8 主力机 · 验证码", spec.text)
        assertFalse(spec.text.contains("482913"))
        assertFalse(spec.bigText.contains("482913"))
        assertFalse(spec.title.contains("95555"))
        assertNull(spec.subText)
        assertFalse(spec.summaryLine.contains("95555"))
    }

    @Test
    fun longAndCjkTextIsClipped() {
        val long = chat.copy(sender = "很长的发件人名字".repeat(10), body = "正文".repeat(600), preview = "预览".repeat(100))
        val spec = NotificationFormatter.format(long, true, texts)
        assertEquals(40, spec.title.codePointCount(0, spec.title.length))
        assertTrue(spec.title.endsWith("…"))
        assertTrue(spec.bigText.codePointCount(0, spec.bigText.length) <= 500)
        assertTrue(spec.text.codePointCount(0, spec.text.length) <= 120)
        // Emoji are never cut in half.
        val emoji = chat.copy(sender = "😺".repeat(60))
        val title = NotificationFormatter.format(emoji, true, texts).title
        assertEquals(40, title.codePointCount(0, title.length))
    }

    @Test
    fun expiredBodyGivesAGenericText() {
        val gone = otp.copy(expiresAt = null, body = null, otp = null, preview = "", sender = "", title = "")
        val spec = NotificationFormatter.format(gone, true, texts)
        assertEquals("正文已过期", spec.text)
        assertFalse(spec.copyAction)
        assertEquals("Fold8 主力机", spec.title)
    }

    @Test
    fun summaryLineUsesTheMaskedPreview() {
        val spec = NotificationFormatter.format(otp, true, texts)
        assertEquals("95555：【招商银行】您的验证码为******，5分钟内有效。", spec.summaryLine)
        assertFalse(spec.summaryLine.contains("482913"))
    }

    @Test
    fun avatarLabel() {
        assertEquals("🏦", NotificationFormatter.avatarLabel("🏦", "招商银行"))
        assertEquals("招", NotificationFormatter.avatarLabel("", "招商银行"))
        assertEquals("C", NotificationFormatter.avatarLabel("", "ci"))
        assertEquals("?", NotificationFormatter.avatarLabel("", " "))
    }
}
