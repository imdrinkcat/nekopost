package com.drinkcat.nekopost.inbox

import com.drinkcat.nekopost.core.forwarder.FakeForwarderData
import com.drinkcat.nekopost.inbox.ui.InboxUiState
import java.time.Duration
import java.time.Instant

/**
 * Deterministic inbox for screenshots and tests: times derive from
 * FakeForwarderData.Now (2026-11-16 10:20 Asia/Tokyo).
 */
object FakeInboxData {
    private val now: Instant = FakeForwarderData.Now
    private val keep: Instant = now.plus(Duration.ofDays(7))

    fun id(n: Int) = "m_01JC" + n.toString().padStart(22, '0')

    private fun ago(minutes: Long) = now.minus(Duration.ofMinutes(minutes))

    val otp = InboxMessage(
        id = id(1), seq = 110, sender = "95555", title = "******",
        preview = "【招商银行】您的验证码为******，5分钟内有效，请勿泄露。",
        sourceId = "fold8", sourceName = "Fold8 主力机", kind = MessageKind.Sms, category = "验证码",
        receivedAt = ago(2), expiresAt = keep, hasCode = true, read = false,
    )
    val otpDetail = otp.copy(
        body = "【招商银行】您的验证码为482913，5分钟内有效，请勿泄露。如非本人操作请忽略。",
        otp = "482913",
        deliveries = listOf(Delivery("telegram", "delivered", false), Delivery("fcm", "delivered", true)),
    )
    val wechat = InboxMessage(
        id = id(2), seq = 109, sender = "微信 · 张三", title = "微信 · 张三", preview = "今晚一起吃饭吗？七点，老地方。",
        sourceId = "fold8", sourceName = "Fold8 主力机", kind = MessageKind.Notification, category = "通知",
        receivedAt = ago(25), expiresAt = keep,
    )
    val ci = InboxMessage(
        id = id(3), seq = 108, sender = "CI · build #42 passed", title = "CI · build #42 passed",
        preview = "nekopost main 4f3a2c1 · 全部 312 个测试通过，用时 4 分 12 秒",
        sourceId = "github", sourceName = "GitHub Actions", sourceIcon = "🛠", kind = MessageKind.Script, category = "脚本",
        receivedAt = ago(68), expiresAt = keep,
    )
    val note = InboxMessage(
        id = id(4), seq = 107, sender = "发给自己", title = "发给自己", preview = "记得周五前续费域名 drinkcat.com",
        sourceId = "fold8", sourceName = "Fold8 主力机", kind = MessageKind.Note, category = "笔记",
        receivedAt = ago(110), expiresAt = keep, read = true,
    )
    val long = InboxMessage(
        id = id(5), seq = 106, sender = "楽天銀行", title = "楽天銀行",
        preview = "【楽天銀行】お振込みを受け付けました。振込先：ヤマダ タロウ 様 金額：******円 詳細はアプリでご確認ください。",
        sourceId = "fold8", sourceName = "Fold8 主力机", kind = MessageKind.Sms, category = "银行",
        receivedAt = ago(190), expiresAt = keep,
    )
    val expired = InboxMessage(
        id = id(6), seq = 105, sourceId = "fold8", sourceName = "Fold8 主力机", kind = MessageKind.Sms, category = "快递",
        receivedAt = ago(260), expiresAt = null, read = true,
    )
    val quiet = InboxMessage(
        id = id(7), seq = 104, sender = "佐川急便", title = "佐川急便", preview = "お荷物は本日 18:00〜20:00 にお届け予定です 📦",
        sourceId = "fold8", sourceName = "Fold8 主力机", kind = MessageKind.Notification, category = "快递",
        receivedAt = ago(300), expiresAt = keep, priority = MessagePriority.Low, read = true,
    )
    val emoji = InboxMessage(
        id = id(8), seq = 103, sender = "LINE · 妈妈", title = "LINE · 妈妈", preview = "到家了吗？🐱 记得吃饭",
        sourceId = "fold8", sourceName = "Fold8 主力机", kind = MessageKind.Notification, category = "通知",
        receivedAt = ago(400), expiresAt = keep, read = true,
    )
    val yesterday = InboxMessage(
        id = id(9), seq = 102, sender = "+81 90-1234-5678", title = "******", preview = "明天的会议改到下午三点。",
        sourceId = "fold8", sourceName = "Fold8 主力机", kind = MessageKind.Sms, category = "短信",
        receivedAt = ago(18 * 60), expiresAt = keep, read = true,
    )
    val old = InboxMessage(
        id = id(10), seq = 101, sender = "Gmail · Cloudflare", title = "Gmail · Cloudflare", preview = "Your domain drinkcat.com will renew soon",
        sourceId = "fold8", sourceName = "Fold8 主力机", kind = MessageKind.Notification, category = "邮件",
        receivedAt = now.minus(Duration.ofDays(21)), expiresAt = keep, read = true,
    )

    val rows = listOf(otp, wechat, ci, note, long, expired, quiet, emoji, yesterday, old)

    val list = InboxUiState(rows = rows, unreadCount = rows.count { !it.read }, canAdmin = true, canSend = true)

    fun withDetail(detail: InboxMessage) = list.copy(selectedId = detail.id, detail = detail)
}
