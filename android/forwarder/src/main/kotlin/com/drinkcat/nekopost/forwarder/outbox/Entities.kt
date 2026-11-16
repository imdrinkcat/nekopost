package com.drinkcat.nekopost.forwarder.outbox

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One message waiting to be uploaded (design §8.7). The request body lives
 * only in [payloadCt], encrypted with the forwarder AEAD (AAD = row id).
 * Accepted rows are deleted; rejected ones stay [STATE_FAILED] for 7 days.
 */
@Suppress("ArrayInDataClass")
@Entity(
    tableName = "outbox",
    indices = [Index(value = ["idem_key"], unique = true), Index(value = ["state", "next_at"])],
)
internal data class OutboxEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "log_id") val logId: Long,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "idem_key") val idemKey: String,
    /** [com.drinkcat.nekopost.core.forwarder.ForwardKind] name. */
    val kind: String,
    @ColumnInfo(name = "payload_ct", typeAffinity = ColumnInfo.BLOB) val payloadCt: ByteArray,
    val state: String,
    val attempts: Int = 0,
    @ColumnInfo(name = "next_at") val nextAt: Long,
    @ColumnInfo(name = "last_error") val lastError: String? = null,
    @ColumnInfo(name = "last_http") val lastHttp: Int? = null,
) {
    companion object {
        const val STATE_PENDING = "pending"
        const val STATE_FAILED = "failed"
    }
}

/** Forward log row: metadata only (never a body). */
@Entity(tableName = "log", indices = [Index(value = ["created_at"])])
internal data class LogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val kind: String,
    val title: String,
    @ColumnInfo(name = "source_package") val sourcePackage: String? = null,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    /** [com.drinkcat.nekopost.core.forwarder.ForwardStatus] name. */
    val status: String,
    val attempts: Int = 0,
    @ColumnInfo(name = "last_http") val lastHttp: Int? = null,
    @ColumnInfo(name = "last_error") val lastError: String? = null,
    @ColumnInfo(name = "server_id") val serverMessageId: String? = null,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)

/** SHA-256 of an idempotency key the server already accepted; kept 7 days for local dedupe. */
@Entity(tableName = "sent_keys")
internal data class SentKeyEntity(
    @PrimaryKey val digest: String,
    @ColumnInfo(name = "sent_at") val sentAt: Long,
)

/**
 * An SMS this device has captured, keyed by a keyed fingerprint (no content).
 * Lets each path recognise what the other already delivered, and feeds the
 * OTP delay statistics (kept 7 days).
 *
 * Matching is one to one, so identical texts from the same sender (a repeated
 * self-test, "余额不足" twice) are still separate SMS: the broadcast path only
 * drops an exact redelivery ([live] row with the same SMSC time) or claims a
 * row the reconciler found first; the reconciler pairs each inbox row with at
 * most one row the broadcast captured ([inboxId]).
 */
@Entity(tableName = "sms_seen", indices = [Index(value = ["fingerprint"]), Index(value = ["seen_at"])])
internal data class SmsSeenEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val fingerprint: String,
    /** SMSC timestamp (0 = unknown). */
    @ColumnInfo(name = "smsc_at") val smscAt: Long,
    /** When this device first saw it. */
    @ColumnInfo(name = "seen_at") val seenAt: Long,
    /** How it was first seen: [VIA_BROADCAST], [VIA_RECONCILER] or [VIA_RECONCILER_EXPLAINED]. */
    val via: String,
    @ColumnInfo(name = "is_code") val isCode: Boolean,
    /** Inbox `_id` of the row the reconciler matched to this SMS; null until then. */
    @ColumnInfo(name = "inbox_id") val inboxId: Long? = null,
    /** Whether the SMS_RECEIVED broadcast delivered this SMS (first or after the reconciler). */
    @ColumnInfo(name = "live", defaultValue = "0") val live: Boolean = false,
) {
    companion object {
        const val VIA_BROADCAST = "broadcast"
        const val VIA_RECONCILER = "reconciler"

        /** Found by the reconciler, but it arrived before boot completed or during an app update. */
        const val VIA_RECONCILER_EXPLAINED = "reconciler_explained"
    }
}

internal data class QueueCounts(
    val pending: Int,
    val failed: Int,
    val oldestPendingAt: Long?,
)
