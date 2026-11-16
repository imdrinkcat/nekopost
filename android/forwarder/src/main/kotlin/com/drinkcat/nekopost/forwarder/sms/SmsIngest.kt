package com.drinkcat.nekopost.forwarder.sms

import com.drinkcat.nekopost.core.forwarder.ForwardKind
import com.drinkcat.nekopost.core.forwarder.MessagePriority
import com.drinkcat.nekopost.forwarder.crypto.Hashing
import com.drinkcat.nekopost.forwarder.crypto.IdempotencyKeys
import com.drinkcat.nekopost.forwarder.outbox.NewItem
import com.drinkcat.nekopost.forwarder.outbox.Outbox
import com.drinkcat.nekopost.forwarder.outbox.OutboxDao
import com.drinkcat.nekopost.forwarder.outbox.OutgoingMessage
import com.drinkcat.nekopost.forwarder.outbox.SmsSeenEntity
import com.drinkcat.nekopost.forwarder.store.ForwarderPrefs
import com.drinkcat.nekopost.forwarder.store.SecretStore
import com.drinkcat.nekopost.forwarder.work.WorkScheduler
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Clock
import java.time.Duration
import java.time.Instant
import kotlin.math.abs

/**
 * Turns captured SMS (live broadcast or inbox scan) into outbox items.
 * Payload (design §8.4): `{type:"sms", title:<sender as-is>, body,
 * occurred_at:<SMSC time>, meta:{sim, parts, via}}`. Contact names are never
 * looked up or sent.
 */
internal class SmsIngest(
    private val prefs: ForwarderPrefs,
    private val secrets: SecretStore,
    private val outbox: Outbox,
    private val dao: OutboxDao,
    private val simLabeler: SimLabeler,
    private val scheduler: WorkScheduler,
    private val clock: Clock,
) {
    private val mutex = Mutex()

    /** Digest → capture time of recent SMS bodies, so an RCS/SMS app notification of the same text is skipped. */
    private val recentBodies = LinkedHashMap<String, Long>()

    /** SMS_RECEIVED path. Returns how many messages were queued. */
    suspend fun onBroadcast(messages: List<IncomingSms>, receivedAt: Instant): Int = capture(
        messages.map { Captured(it.address, it.subId, it.body, it.timestampMillis, it.parts) },
        observedAt = receivedAt,
        via = SmsSeenEntity.VIA_BROADCAST,
    )

    /** Inbox-scan path (rows already filtered by start point and lookback). */
    suspend fun onReconciled(rows: List<InboxSms>, scannedAt: Instant): Int = capture(
        rows.map { Captured(it.address, it.subId, it.body, it.smscMillis, 1) },
        observedAt = scannedAt,
        via = SmsSeenEntity.VIA_RECONCILER,
    )

    /** Whether this SMS was already captured (by either path). */
    suspend fun isKnown(address: String, body: String, smscMillis: Long): Boolean {
        val fingerprint = IdempotencyKeys.smsFingerprint(secrets.deviceSecret(), address, body)
        return seen(fingerprint, smscMillis)
    }

    /** True if an SMS with exactly this body was captured in the last 2 minutes. */
    fun recentlyCapturedBody(body: String, now: Instant): Boolean {
        val at = synchronized(recentBodies) { recentBodies[Hashing.sha256Hex(body.trim())] } ?: return false
        return now.toEpochMilli() - at <= RECENT_BODY_WINDOW.toMillis()
    }

    private data class Captured(val address: String, val subId: Int, val body: String, val smscMillis: Long, val parts: Int)

    private suspend fun capture(items: List<Captured>, observedAt: Instant, via: String): Int {
        if (items.isEmpty()) return 0
        val settings = prefs.current()
        if (settings.enrollment == null || !settings.smsEnabled) return 0
        var queued = 0
        mutex.withLock {
            val secret = secrets.deviceSecret()
            for (sms in items) {
                val fingerprint = IdempotencyKeys.smsFingerprint(secret, sms.address, sms.body)
                if (seen(fingerprint, sms.smscMillis)) continue
                dao.insertSeen(
                    SmsSeenEntity(
                        fingerprint = fingerprint,
                        smscAt = sms.smscMillis,
                        seenAt = observedAt.toEpochMilli(),
                        via = via,
                        isCode = OtpDetector.looksLikeCode(sms.body),
                    ),
                )
                rememberBody(sms.body, observedAt)
                val meta = buildMap<String, Any> {
                    simLabeler.label(sms.subId)?.let { put("sim", it) }
                    if (sms.parts > 1) put("parts", sms.parts)
                    if (via == SmsSeenEntity.VIA_RECONCILER) put("via", "reconciler")
                }
                val message = OutgoingMessage(
                    type = "sms",
                    title = sms.address,
                    body = sms.body,
                    priority = MessagePriority.Normal,
                    occurredAt = if (sms.smscMillis > 0) Instant.ofEpochMilli(sms.smscMillis) else observedAt,
                    meta = meta,
                )
                val key = IdempotencyKeys.sms(secret, sms.subId, sms.address, sms.smscMillis, sms.body)
                if (outbox.enqueue(NewItem(ForwardKind.Sms, sms.address, null, key, message)) != null) queued++
            }
        }
        if (queued > 0) scheduler.uploadNow()
        return queued
    }

    private suspend fun seen(fingerprint: String, smscMillis: Long): Boolean =
        dao.seenByFingerprint(fingerprint).any { abs(it.smscAt - smscMillis) <= SAME_SMS_TOLERANCE.toMillis() }

    private fun rememberBody(body: String, at: Instant) {
        val now = at.toEpochMilli()
        synchronized(recentBodies) {
            recentBodies.entries.removeAll { now - it.value > RECENT_BODY_WINDOW.toMillis() }
            recentBodies[Hashing.sha256Hex(body.trim())] = now
        }
    }

    companion object {
        /** Broadcast and inbox timestamps of the same SMS can differ (segments, clock sources). */
        val SAME_SMS_TOLERANCE: Duration = Duration.ofHours(1)
        val RECENT_BODY_WINDOW: Duration = Duration.ofMinutes(2)
    }
}
