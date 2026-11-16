package com.drinkcat.nekopost.forwarder.sms

import com.drinkcat.nekopost.core.forwarder.ForwardKind
import com.drinkcat.nekopost.core.forwarder.MessagePriority
import com.drinkcat.nekopost.core.rules.RuleAction
import com.drinkcat.nekopost.core.rules.SmsSenderRules
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
 *
 * Each SMS is matched one to one against what the other path already
 * captured (see [SmsSeenEntity]), so a repeated identical text is forwarded
 * again while a redelivery or the inbox copy of a live SMS is not.
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
        messages.map { Captured(it.address, it.subId, it.body, it.timestampMillis, it.parts, inboxId = null) },
        observedAt = receivedAt,
        live = true,
    )

    /** Inbox-scan path (rows already filtered by start point and lookback). */
    suspend fun onReconciled(rows: List<InboxSms>, scannedAt: Instant): Int = capture(
        rows.map { Captured(it.address, it.subId, it.body, it.smscMillis, 1, inboxId = it.id) },
        observedAt = scannedAt,
        live = false,
    )

    /**
     * The inbox rows of [rows] that no path has captured yet (read-only; the
     * matching is redone and recorded by [onReconciled]). Empty when not enrolled.
     */
    suspend fun uncaptured(rows: List<InboxSms>): List<InboxSms> {
        if (rows.isEmpty()) return emptyList()
        return mutex.withLock { uncapturedLocked(rows) }
    }

    private suspend fun uncapturedLocked(rows: List<InboxSms>): List<InboxSms> {
        val settings = prefs.current()
        if (settings.enrollment == null || !settings.smsEnabled) return emptyList()
        val secret = secrets.deviceSecret()
        val claimed = HashSet<Long>()
        return rows.filter { row ->
            val fingerprint = IdempotencyKeys.smsFingerprint(secret, row.address, row.body)
            when (val m = matchInbox(dao.seenByFingerprint(fingerprint), row.id, row.smscMillis, claimed)) {
                Match.Known -> false
                is Match.Claim -> {
                    claimed += m.seenId
                    false
                }
                Match.New -> true
            }
        }
    }

    /** True if an SMS with exactly this body was captured in the last 2 minutes. */
    fun recentlyCapturedBody(body: String, now: Instant): Boolean {
        val at = synchronized(recentBodies) { recentBodies[Hashing.sha256Hex(body.trim())] } ?: return false
        return now.toEpochMilli() - at <= RECENT_BODY_WINDOW.toMillis()
    }

    /** Runs [block] while no SMS is being captured (unenroll). */
    suspend fun <T> exclusive(block: suspend () -> T): T = mutex.withLock { block() }

    private data class Captured(
        val address: String,
        val subId: Int,
        val body: String,
        val smscMillis: Long,
        val parts: Int,
        val inboxId: Long?,
    )

    private sealed interface Match {
        /** Already captured by this path (redelivery, or an inbox row matched before). */
        data object Known : Match

        /** Captured by the other path: record that this path saw it too, queue nothing. */
        data class Claim(val seenId: Long) : Match

        data object New : Match
    }

    private suspend fun capture(items: List<Captured>, observedAt: Instant, live: Boolean): Int {
        if (items.isEmpty()) return 0
        var queued = 0
        mutex.withLock {
            // Read under the lock: unenroll holds it while it wipes everything.
            val settings = prefs.current()
            if (settings.enrollment == null || !settings.smsEnabled) return 0
            val secret = secrets.deviceSecret()
            val claimed = HashSet<Long>()
            for (sms in items) {
                val fingerprint = IdempotencyKeys.smsFingerprint(secret, sms.address, sms.body)
                val seen = dao.seenByFingerprint(fingerprint)
                val match = if (live) {
                    matchBroadcast(seen, sms.smscMillis, claimed)
                } else {
                    matchInbox(seen, sms.inboxId ?: 0, sms.smscMillis, claimed)
                }
                when (match) {
                    Match.Known -> continue
                    is Match.Claim -> {
                        claimed += match.seenId
                        if (live) dao.setSeenLive(match.seenId) else dao.setSeenInboxId(match.seenId, sms.inboxId ?: 0)
                        continue
                    }
                    Match.New -> Unit
                }
                val record = SmsSeenEntity(
                    fingerprint = fingerprint,
                    smscAt = sms.smscMillis,
                    seenAt = observedAt.toEpochMilli(),
                    via = via(live, sms.smscMillis, settings.lastRestartAt),
                    isCode = OtpDetector.looksLikeCode(sms.body),
                    inboxId = sms.inboxId,
                    live = live,
                )
                if (SmsSenderRules.decide(settings.smsSenderRules, sms.address) == RuleAction.Skip) {
                    outbox.logSkipped(ForwardKind.Sms, sms.address, null, "rule", seen = record)
                    // Remember skipped bodies too, so the SMS app's notification
                    // of a skipped SMS is not forwarded as a notification either.
                    rememberBody(sms.body, observedAt)
                    continue
                }
                val meta = buildMap<String, Any> {
                    simLabeler.label(sms.subId)?.let { put("sim", it) }
                    if (sms.parts > 1) put("parts", sms.parts)
                    if (!live) put("via", "reconciler")
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
                // The seen record is written in the same transaction as the outbox row.
                if (outbox.enqueue(NewItem(ForwardKind.Sms, sms.address, null, key, message), seen = record) != null) queued++
                rememberBody(sms.body, observedAt)
            }
        }
        if (queued > 0) scheduler.uploadNow()
        return queued
    }

    /**
     * Broadcast: the same SMS again is an exact redelivery (same SMSC time);
     * otherwise it may be one the reconciler found first. A new SMS with the
     * same text from the same sender has a different SMSC time.
     */
    private fun matchBroadcast(seen: List<SmsSeenEntity>, smscMillis: Long, claimed: Set<Long>): Match {
        if (seen.any { it.live && it.smscAt == smscMillis }) return Match.Known
        val first = seen.filter { !it.live && it.id !in claimed && near(it.smscAt, smscMillis) }
            .minByOrNull { abs(it.smscAt - smscMillis) }
        return if (first != null) Match.Claim(first.id) else Match.New
    }

    /**
     * Inbox scan: an inbox row matches at most one captured SMS, and each
     * captured SMS matches at most one inbox row.
     */
    private fun matchInbox(seen: List<SmsSeenEntity>, inboxId: Long, smscMillis: Long, claimed: Set<Long>): Match {
        if (seen.any { it.inboxId == inboxId && near(it.smscAt, smscMillis) }) return Match.Known
        val live = seen.filter { it.inboxId == null && it.id !in claimed && near(it.smscAt, smscMillis) }
            .minByOrNull { abs(it.smscAt - smscMillis) }
        return if (live != null) Match.Claim(live.id) else Match.New
    }

    private fun near(a: Long, b: Long): Boolean = abs(a - b) <= SAME_SMS_TOLERANCE.toMillis()

    /** Reconciled SMS that arrived before the last boot or app update were missed for a known reason. */
    private fun via(live: Boolean, smscMillis: Long, lastRestartAt: Long?): String = when {
        live -> SmsSeenEntity.VIA_BROADCAST
        lastRestartAt != null && smscMillis > 0 && smscMillis <= lastRestartAt + RESTART_GRACE.toMillis() ->
            SmsSeenEntity.VIA_RECONCILER_EXPLAINED
        else -> SmsSeenEntity.VIA_RECONCILER
    }

    private fun rememberBody(body: String, at: Instant) {
        val now = at.toEpochMilli()
        synchronized(recentBodies) {
            recentBodies.entries.removeAll { now - it.value > RECENT_BODY_WINDOW.toMillis() }
            recentBodies[Hashing.sha256Hex(body.trim())] = now
        }
    }

    companion object {
        /**
         * How far apart the two paths' timestamps of one SMS may be (inbox
         * `date` stands in when the provider has no `date_sent`). Only used to
         * pair one broadcast capture with one inbox row, never to drop a
         * second SMS with the same text.
         */
        val SAME_SMS_TOLERANCE: Duration = Duration.ofHours(1)
        val RECENT_BODY_WINDOW: Duration = Duration.ofMinutes(2)

        /** SMS this soon after BOOT_COMPLETED / MY_PACKAGE_REPLACED may still have missed the receiver. */
        val RESTART_GRACE: Duration = Duration.ofMinutes(1)
    }
}
