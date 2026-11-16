package com.drinkcat.nekopost.forwarder.outbox

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
internal abstract class OutboxDao {

    // --- enqueue -------------------------------------------------------------

    @Insert
    abstract suspend fun insertLog(log: LogEntity): Long

    @Insert
    abstract suspend fun insertOutbox(row: OutboxEntity): Long

    @Query("UPDATE outbox SET payload_ct = :ct WHERE id = :id")
    abstract suspend fun setPayload(id: Long, ct: ByteArray)

    @Query("SELECT COUNT(*) FROM outbox WHERE idem_key = :key")
    abstract suspend fun countIdemKey(key: String): Int

    @Query("SELECT COUNT(*) FROM sent_keys WHERE digest = :digest")
    abstract suspend fun countSent(digest: String): Int

    /**
     * Inserts the log row and the outbox row, then stores the payload sealed
     * with the new row id as AAD. Returns null (and writes no log or outbox
     * row) when the idempotency key is already queued or was accepted in the
     * last 7 days. [seen] (a captured SMS) is recorded either way, in the same
     * transaction: if sealing fails, the SMS is not marked seen and the
     * reconciler picks it up again.
     */
    @Transaction
    open suspend fun enqueue(
        log: LogEntity,
        row: OutboxEntity,
        sentDigest: String,
        seen: SmsSeenEntity?,
        seal: (Long) -> ByteArray,
    ): Long? {
        if (seen != null) insertSeen(seen)
        if (countIdemKey(row.idemKey) > 0 || countSent(sentDigest) > 0) return null
        val logId = insertLog(log)
        val id = insertOutbox(row.copy(logId = logId))
        setPayload(id, seal(id))
        return id
    }

    /** A Skipped log row and, for an SMS, its seen record, written together. */
    @Transaction
    open suspend fun insertSkipped(log: LogEntity, seen: SmsSeenEntity?) {
        if (seen != null) insertSeen(seen)
        insertLog(log)
    }

    // --- upload --------------------------------------------------------------

    @Query("SELECT * FROM outbox WHERE state = 'pending' AND next_at <= :now ORDER BY next_at, id LIMIT :limit")
    abstract suspend fun due(now: Long, limit: Int): List<OutboxEntity>

    @Query("SELECT * FROM outbox WHERE id = :id")
    abstract suspend fun get(id: Long): OutboxEntity?

    @Query("SELECT * FROM outbox ORDER BY id")
    abstract suspend fun all(): List<OutboxEntity>

    @Query("SELECT MIN(next_at) FROM outbox WHERE state = 'pending'")
    abstract suspend fun nextPendingAt(): Long?

    @Query("SELECT COUNT(*) FROM outbox WHERE state = 'pending'")
    abstract suspend fun pendingCount(): Int

    @Query("SELECT MIN(created_at) FROM outbox WHERE state = 'pending'")
    abstract suspend fun oldestPendingAt(): Long?

    @Query(
        "UPDATE outbox SET state = :state, attempts = :attempts, next_at = :nextAt, last_error = :error, " +
            "last_http = :http WHERE id = :id",
    )
    abstract suspend fun updateOutbox(id: Long, state: String, attempts: Int, nextAt: Long, error: String?, http: Int?)

    /** Queue-wide backoff: nothing pending is tried before [nextAt]. */
    @Query("UPDATE outbox SET next_at = :nextAt WHERE state = 'pending' AND next_at < :nextAt")
    abstract suspend fun deferPending(nextAt: Long)

    @Query("UPDATE outbox SET next_at = :now WHERE state = 'pending'")
    abstract suspend fun makeAllDue(now: Long)

    @Query("DELETE FROM outbox WHERE id = :id")
    abstract suspend fun deleteOutbox(id: Long)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun insertSent(key: SentKeyEntity)

    @Transaction
    open suspend fun markAccepted(row: OutboxEntity, sentDigest: String, logStatus: String, attempts: Int, http: Int, serverId: String?, now: Long) {
        deleteOutbox(row.id)
        insertSent(SentKeyEntity(sentDigest, now))
        updateLog(row.logId, logStatus, attempts, http, null, serverId, now)
    }

    @Query("DELETE FROM outbox WHERE state = 'failed'")
    abstract suspend fun deleteFailed(): Int

    @Query(
        "SELECT COUNT(CASE WHEN state = 'pending' THEN 1 END) AS pending, " +
            "COUNT(CASE WHEN state = 'failed' THEN 1 END) AS failed, " +
            "MIN(CASE WHEN state = 'pending' THEN created_at END) AS oldestPendingAt FROM outbox",
    )
    abstract fun queueCounts(): Flow<QueueCounts>

    // --- log -----------------------------------------------------------------

    @Query(
        "UPDATE log SET status = :status, attempts = :attempts, last_http = :http, last_error = :error, " +
            "server_id = COALESCE(:serverId, server_id), updated_at = :now WHERE id = :id",
    )
    abstract suspend fun updateLog(id: Long, status: String, attempts: Int, http: Int?, error: String?, serverId: String?, now: Long)

    @Query("UPDATE log SET status = :status, updated_at = :now WHERE id IN (SELECT log_id FROM outbox WHERE state = 'pending')")
    abstract suspend fun setPendingLogStatus(status: String, now: Long)

    @Query("SELECT * FROM log ORDER BY created_at DESC, id DESC LIMIT :limit")
    abstract fun logFlow(limit: Int): Flow<List<LogEntity>>

    @Query("SELECT * FROM log ORDER BY id DESC LIMIT :limit")
    abstract suspend fun latestLog(limit: Int): List<LogEntity>

    // --- SMS seen / OTP ------------------------------------------------------

    @Insert
    abstract suspend fun insertSeen(seen: SmsSeenEntity): Long

    @Query("SELECT * FROM sms_seen WHERE fingerprint = :fingerprint ORDER BY id")
    abstract suspend fun seenByFingerprint(fingerprint: String): List<SmsSeenEntity>

    /** The reconciler matched a live-captured SMS to its inbox row. */
    @Query("UPDATE sms_seen SET inbox_id = :inboxId WHERE id = :id")
    abstract suspend fun setSeenInboxId(id: Long, inboxId: Long)

    /** The broadcast delivered an SMS the reconciler had already found. */
    @Query("UPDATE sms_seen SET live = 1 WHERE id = :id")
    abstract suspend fun setSeenLive(id: Long)

    @Query("SELECT * FROM sms_seen WHERE is_code = 1 ORDER BY seen_at DESC LIMIT 1000")
    abstract fun codeEvents(): Flow<List<SmsSeenEntity>>

    // --- retention (design §8.7) ---------------------------------------------

    @Query("DELETE FROM outbox WHERE state = 'failed' AND created_at < :before")
    abstract suspend fun deleteFailedBefore(before: Long): Int

    @Query(
        "UPDATE log SET status = 'Failed', last_error = 'expired', updated_at = :now " +
            "WHERE id IN (SELECT log_id FROM outbox WHERE state = 'pending' AND created_at < :before)",
    )
    abstract suspend fun expirePendingLogs(before: Long, now: Long)

    @Query("DELETE FROM outbox WHERE state = 'pending' AND created_at < :before")
    abstract suspend fun deletePendingBefore(before: Long): Int

    @Query("SELECT COUNT(*) FROM outbox")
    abstract suspend fun outboxCount(): Int

    @Query(
        "UPDATE log SET status = 'Failed', last_error = 'queue_full', updated_at = :now " +
            "WHERE id IN (SELECT log_id FROM outbox ORDER BY id LIMIT :count)",
    )
    abstract suspend fun dropOldestLogs(count: Int, now: Long)

    @Query("DELETE FROM outbox WHERE id IN (SELECT id FROM outbox ORDER BY id LIMIT :count)")
    abstract suspend fun dropOldest(count: Int): Int

    @Query("DELETE FROM sent_keys WHERE sent_at < :before")
    abstract suspend fun pruneSent(before: Long)

    @Query("DELETE FROM sms_seen WHERE seen_at < :before")
    abstract suspend fun pruneSeen(before: Long)

    @Query(
        "DELETE FROM log WHERE id NOT IN (SELECT id FROM log ORDER BY id DESC LIMIT :keep) " +
            "AND id NOT IN (SELECT log_id FROM outbox)",
    )
    abstract suspend fun trimLog(keep: Int)

    /** Retention pass: returns how many queued items were dropped (expired or over capacity). */
    @Transaction
    open suspend fun prune(now: Long, maxRows: Int, pendingMaxAge: Long, failedMaxAge: Long, keysMaxAge: Long, keepLog: Int): Int {
        deleteFailedBefore(now - failedMaxAge)
        expirePendingLogs(now - pendingMaxAge, now)
        var dropped = deletePendingBefore(now - pendingMaxAge)
        val excess = outboxCount() - maxRows
        if (excess > 0) {
            dropOldestLogs(excess, now)
            dropped += dropOldest(excess)
        }
        pruneSent(now - keysMaxAge)
        pruneSeen(now - keysMaxAge)
        trimLog(keepLog)
        return dropped
    }
}
