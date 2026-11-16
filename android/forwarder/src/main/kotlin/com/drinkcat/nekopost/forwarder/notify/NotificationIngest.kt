package com.drinkcat.nekopost.forwarder.notify

import com.drinkcat.nekopost.core.forwarder.ForwardKind
import com.drinkcat.nekopost.core.forwarder.MessagePriority
import com.drinkcat.nekopost.core.forwarder.NotificationAppPolicy
import com.drinkcat.nekopost.core.forwarder.NotificationRule
import com.drinkcat.nekopost.forwarder.crypto.Hashing
import com.drinkcat.nekopost.forwarder.crypto.IdempotencyKeys
import com.drinkcat.nekopost.forwarder.outbox.NewItem
import com.drinkcat.nekopost.forwarder.outbox.Outbox
import com.drinkcat.nekopost.forwarder.outbox.OutgoingMessage
import com.drinkcat.nekopost.forwarder.sms.SmsIngest
import com.drinkcat.nekopost.forwarder.store.ForwarderPrefs
import com.drinkcat.nekopost.forwarder.work.WorkScheduler
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Clock
import java.time.Duration
import java.time.Instant

internal sealed interface NotificationDecision {
    /** Forwarding is off, the app is not selected, or it can never be selected. */
    data object NotSelected : NotificationDecision

    /** Same notification key and content within 10 minutes. */
    data object Duplicate : NotificationDecision

    /** Logged as Skipped (rule or already forwarded as SMS). */
    data class Skipped(val reason: String) : NotificationDecision

    /** Over 30/min for this app; counted into a summary sent at [flushAt] (epoch ms). */
    data class Coalesced(val flushAt: Long, val first: Boolean) : NotificationDecision

    data class Queued(val outboxId: Long) : NotificationDecision
}

/**
 * Notification forwarding rules (design §8.5): only user-selected apps, never
 * Telegram clients or ourselves; per-app keyword rules; dedupe by
 * (key, content hash) for 10 minutes; at most 30 per app per minute, the rest
 * merged into one "N 条通知" message; redacted content becomes a placeholder
 * message with meta.redacted = true.
 */
internal class NotificationIngest(
    private val prefs: ForwarderPrefs,
    private val outbox: Outbox,
    private val scheduler: WorkScheduler,
    private val clock: Clock,
    private val appLabeler: AppLabeler,
    private val smsIngest: SmsIngest,
    private val ownPackage: String,
) {
    private val mutex = Mutex()
    private val recent = HashMap<String, Long>()
    private val windows = HashMap<String, RateWindow>()

    private class RateWindow(var start: Long) {
        var count = 0
        var suppressed = 0
    }

    suspend fun onPosted(n: CapturedNotification): NotificationDecision = mutex.withLock { decide(n) }

    /** Sends the summaries of rate windows that have ended. Returns how many were queued. */
    suspend fun flushDue(): Int = mutex.withLock {
        val now = clock.millis()
        var queued = 0
        for ((pkg, window) in windows) {
            if (now - window.start >= RATE_WINDOW.toMillis() && window.suppressed > 0) {
                if (queueSummary(pkg, window.suppressed)) queued++
                window.suppressed = 0
            }
        }
        if (queued > 0) scheduler.uploadNow()
        queued
    }

    /** Forget in-memory dedupe and rate state (unenroll). */
    suspend fun reset() = mutex.withLock {
        recent.clear()
        windows.clear()
    }

    private suspend fun decide(n: CapturedNotification): NotificationDecision {
        val settings = prefs.current()
        val pkg = n.packageName
        if (settings.enrollment == null || !settings.notificationsEnabled) return NotificationDecision.NotSelected
        if (pkg == ownPackage || !NotificationAppPolicy.isSelectable(pkg) || pkg !in settings.notificationApps) {
            return NotificationDecision.NotSelected
        }
        val now = clock.millis()
        val label = appLabeler.label(pkg)
        val rule = settings.notificationRules[pkg] ?: NotificationRule.Default

        if (!n.redacted && !rule.accepts(n.title, n.text)) {
            outbox.logSkipped(ForwardKind.Notification, label, pkg, "rule")
            return NotificationDecision.Skipped("rule")
        }
        // RCS / SMS app notifications repeat an SMS we already forwarded.
        if (n.text != null && smsIngest.recentlyCapturedBody(n.text, Instant.ofEpochMilli(now))) {
            outbox.logSkipped(ForwardKind.Notification, label, pkg, "sms_duplicate")
            return NotificationDecision.Skipped("sms_duplicate")
        }

        recent.entries.removeAll { now - it.value >= DEDUPE_WINDOW.toMillis() }
        val dedupeKey = n.key + "|" + Hashing.sha256Hex(listOf(n.title.orEmpty(), n.text.orEmpty(), n.redacted.toString()).joinToString("\u0000"))
        if (recent.containsKey(dedupeKey)) return NotificationDecision.Duplicate
        recent[dedupeKey] = now

        val window = windows.getOrPut(pkg) { RateWindow(now) }
        if (now - window.start >= RATE_WINDOW.toMillis()) {
            if (window.suppressed > 0) queueSummary(pkg, window.suppressed)
            window.start = now
            window.count = 0
            window.suppressed = 0
        }
        if (window.count >= RATE_LIMIT) {
            window.suppressed++
            return NotificationDecision.Coalesced(window.start + RATE_WINDOW.toMillis(), first = window.suppressed == 1)
        }
        window.count++

        val title = if (rule.includeTitle && n.title != null) "$label · ${n.title}" else label
        val meta = buildMap<String, Any> {
            put("pkg", pkg)
            n.channelId?.let { put("channel", it) }
            if (n.redacted) put("redacted", true)
        }
        val message = OutgoingMessage(
            type = "notification",
            title = title,
            body = if (n.redacted) REDACTED_BODY else n.text,
            priority = rule.priority,
            occurredAt = Instant.ofEpochMilli(n.postTime),
            meta = meta,
        )
        val id = outbox.enqueue(NewItem(ForwardKind.Notification, label, pkg, IdempotencyKeys.random(), message))
            ?: return NotificationDecision.Duplicate
        scheduler.uploadNow()
        return NotificationDecision.Queued(id)
    }

    private suspend fun queueSummary(pkg: String, count: Int): Boolean {
        val label = appLabeler.label(pkg)
        val message = OutgoingMessage(
            type = "notification",
            title = "$label · $count 条通知",
            body = "1 分钟内通知超过 $RATE_LIMIT 条，其余 $count 条已合并，未逐条转发。",
            priority = MessagePriority.Low,
            occurredAt = clock.instant(),
            meta = mapOf("pkg" to pkg, "coalesced" to count),
        )
        return outbox.enqueue(NewItem(ForwardKind.Notification, label, pkg, IdempotencyKeys.random(), message)) != null
    }

    companion object {
        const val RATE_LIMIT = 30
        val RATE_WINDOW: Duration = Duration.ofMinutes(1)
        val DEDUPE_WINDOW: Duration = Duration.ofMinutes(10)
        const val REDACTED_BODY = "系统隐藏了验证码内容"
    }
}
