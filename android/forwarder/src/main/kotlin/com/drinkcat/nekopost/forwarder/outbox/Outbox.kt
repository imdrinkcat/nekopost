package com.drinkcat.nekopost.forwarder.outbox

import com.drinkcat.nekopost.core.forwarder.ForwardKind
import com.drinkcat.nekopost.core.forwarder.ForwardLogItem
import com.drinkcat.nekopost.core.forwarder.ForwardStatus
import com.drinkcat.nekopost.forwarder.crypto.Hashing
import com.drinkcat.nekopost.forwarder.crypto.KeyManager
import java.time.Clock
import java.time.Duration
import java.time.Instant

/** What to queue: [message] is sealed, the rest is plain metadata for the log. */
internal data class NewItem(
    val kind: ForwardKind,
    /** Log title: SMS sender, app label or "测试消息"; never content. */
    val logTitle: String,
    val sourcePackage: String?,
    val idemKey: String,
    val message: OutgoingMessage,
)

/** Persist first, upload later (design §8.7). */
internal class Outbox(
    private val db: ForwarderDatabase,
    private val keys: KeyManager,
    private val clock: Clock,
) {
    private val dao = db.dao()

    /** Queues [item]; returns the outbox id, or null if its idempotency key is already queued or sent. */
    suspend fun enqueue(item: NewItem): Long? {
        val now = clock.millis()
        val plain = item.message.toJson(clock.zone).toByteArray(Charsets.UTF_8)
        val log = LogEntity(
            kind = item.kind.name,
            title = OutgoingMessage.clipTitle(item.logTitle),
            sourcePackage = item.sourcePackage,
            createdAt = now,
            status = ForwardStatus.Queued.name,
            updatedAt = now,
        )
        val row = OutboxEntity(
            logId = 0,
            createdAt = now,
            idemKey = item.idemKey,
            kind = item.kind.name,
            payloadCt = ByteArray(0),
            state = OutboxEntity.STATE_PENDING,
            nextAt = now,
        )
        return dao.enqueue(log, row, sentDigest(item.idemKey)) { id -> keys.aead().encrypt(plain, aad(id)) }
    }

    /** Records an item dropped by a local rule ([ForwardStatus.Skipped]); nothing is queued. */
    suspend fun logSkipped(kind: ForwardKind, title: String, sourcePackage: String?, reason: String) {
        val now = clock.millis()
        dao.insertLog(
            LogEntity(
                kind = kind.name,
                title = OutgoingMessage.clipTitle(title),
                sourcePackage = sourcePackage,
                createdAt = now,
                status = ForwardStatus.Skipped.name,
                lastError = reason,
                updatedAt = now,
            ),
        )
    }

    /** Decrypts a row's request body. Throws GeneralSecurityException if the key is gone or the row was moved. */
    fun open(row: OutboxEntity): String = keys.aead().decrypt(row.payloadCt, aad(row.id)).toString(Charsets.UTF_8)

    /** Retention: failed 7 days, pending 30 days, at most 5000 rows; returns the number of dropped items. */
    suspend fun prune(): Int = dao.prune(
        now = clock.millis(),
        maxRows = MAX_ROWS,
        pendingMaxAge = Duration.ofDays(30).toMillis(),
        failedMaxAge = Duration.ofDays(7).toMillis(),
        keysMaxAge = Duration.ofDays(7).toMillis(),
        keepLog = KEEP_LOG,
    )

    companion object {
        const val MAX_ROWS = 5000
        const val KEEP_LOG = 500

        fun aad(id: Long): ByteArray = "nekopost/outbox/$id".toByteArray(Charsets.UTF_8)

        fun sentDigest(idemKey: String): String = Hashing.sha256Hex(idemKey)
    }
}

internal fun LogEntity.toItem(): ForwardLogItem = ForwardLogItem(
    id = id,
    kind = ForwardKind.entries.firstOrNull { it.name == kind } ?: ForwardKind.Notification,
    title = title,
    sourcePackage = sourcePackage,
    createdAt = Instant.ofEpochMilli(createdAt),
    status = ForwardStatus.entries.firstOrNull { it.name == status } ?: ForwardStatus.Queued,
    attempts = attempts,
    lastHttpStatus = lastHttp,
    lastError = lastError,
    serverMessageId = serverMessageId,
    updatedAt = Instant.ofEpochMilli(updatedAt),
)
