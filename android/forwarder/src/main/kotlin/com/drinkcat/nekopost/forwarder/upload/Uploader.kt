package com.drinkcat.nekopost.forwarder.upload

import com.drinkcat.nekopost.core.forwarder.DeliveryEvent
import com.drinkcat.nekopost.core.forwarder.Enrollment
import com.drinkcat.nekopost.core.forwarder.FailureEvent
import com.drinkcat.nekopost.core.forwarder.FailureReason
import com.drinkcat.nekopost.core.forwarder.ForwardKind
import com.drinkcat.nekopost.core.forwarder.ForwardStatus
import com.drinkcat.nekopost.core.forwarder.PauseReason
import com.drinkcat.nekopost.forwarder.outbox.ForwarderDatabase
import com.drinkcat.nekopost.forwarder.outbox.Outbox
import com.drinkcat.nekopost.forwarder.outbox.OutboxEntity
import com.drinkcat.nekopost.forwarder.store.ForwarderPrefs
import com.drinkcat.nekopost.forwarder.store.SecretStore
import com.drinkcat.nekopost.forwarder.work.WorkScheduler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.security.GeneralSecurityException
import java.time.Clock
import java.time.Duration

/**
 * Drains the outbox (design §8.7). One upload at a time; the first retryable
 * failure stops the run and pushes the whole queue back (no hammering a
 * server that is down or rate limiting), 401/403 pause forwarding without
 * dropping anything, 400/413/415/422 mark only that item failed.
 */
internal class Uploader(
    db: ForwarderDatabase,
    private val outbox: Outbox,
    private val prefs: ForwarderPrefs,
    private val secrets: SecretStore,
    private val http: HttpClients,
    private val scheduler: WorkScheduler,
    private val clock: Clock,
) {
    private val dao = db.dao()
    private val mutex = Mutex()

    /** Runs [block] while no upload is in flight (used by unenroll). */
    suspend fun <T> exclusive(block: suspend () -> T): T = mutex.withLock { block() }

    /**
     * Sends every due item. Does nothing while paused unless [ignorePause]
     * (the hourly check whether a disabled source was re-enabled).
     * Returns the number of accepted items.
     */
    suspend fun drain(ignorePause: Boolean = false): Int {
        return mutex.withLock { drainLocked(ignorePause) }
    }

    private suspend fun drainLocked(ignorePause: Boolean): Int {
        val settings = prefs.current()
        val enrollment = settings.enrollment ?: return 0
        if (settings.paused != null && !ignorePause) return 0
        val api = apiFor(enrollment) ?: return pauseWithoutToken()
        var accepted = 0
        run loop@{
            while (true) {
                val batch = dao.due(clock.millis(), BATCH)
                if (batch.isEmpty()) return@loop
                for (row in batch) {
                    when (send(api, row)) {
                        is UploadOutcome.Accepted -> accepted++
                        is UploadOutcome.Rejected -> Unit
                        is UploadOutcome.Retry, is UploadOutcome.Paused -> return@loop
                    }
                }
            }
        }
        scheduleNext()
        return accepted
    }

    /** Sends one item right away, even while paused (the "send test" button). Null if it no longer exists. */
    suspend fun sendOne(outboxId: Long): UploadOutcome? {
        return mutex.withLock { sendOneLocked(outboxId) }
    }

    private suspend fun sendOneLocked(outboxId: Long): UploadOutcome? {
        val enrollment = prefs.current().enrollment ?: return null
        val api = apiFor(enrollment)
        if (api == null) {
            pauseWithoutToken()
            return UploadOutcome.Paused(PauseReason.Unauthorized, null, "token_unreadable")
        }
        val row = dao.get(outboxId) ?: return null
        val outcome = send(api, row)
        scheduleNext()
        return outcome
    }

    private fun apiFor(enrollment: Enrollment): ApiClient? {
        val token = secrets.token() ?: return null
        val url = enrollment.serverUrl.toHttpUrlOrNull() ?: return null
        return ApiClient(url, token, http.forServer(url), clock::instant)
    }

    /** The token is unreadable (key lost after a restore or data wipe): same as 401, re-enroll. */
    private suspend fun pauseWithoutToken(): Int {
        prefs.setPaused(PauseReason.Unauthorized)
        prefs.recordFailure(FailureEvent(clock.instant(), FailureReason.Unauthorized, null, "token_unreadable"))
        dao.setPendingLogStatus(ForwardStatus.Paused.name, clock.millis())
        return 0
    }

    private suspend fun send(api: ApiClient, row: OutboxEntity): UploadOutcome {
        val attempts = row.attempts + 1
        val json = try {
            outbox.open(row)
        } catch (e: GeneralSecurityException) {
            val outcome = UploadOutcome.Rejected(FailureReason.Rejected, null, "undecryptable")
            apply(row, attempts, outcome)
            return outcome
        }
        dao.updateLog(row.logId, ForwardStatus.Sending.name, attempts, row.lastHttp, row.lastError, null, clock.millis())
        val outcome = withContext(Dispatchers.IO) { api.postMessage(json, row.idemKey) }
        apply(row, attempts, outcome)
        return outcome
    }

    private suspend fun apply(row: OutboxEntity, attempts: Int, outcome: UploadOutcome) {
        val now = clock.instant()
        val ms = now.toEpochMilli()
        val kind = ForwardKind.entries.firstOrNull { it.name == row.kind } ?: ForwardKind.Notification
        when (outcome) {
            is UploadOutcome.Accepted -> {
                val status = if (outcome.duplicate) ForwardStatus.Duplicate else ForwardStatus.Accepted
                dao.markAccepted(row, Outbox.sentDigest(row.idemKey), status.name, attempts, outcome.httpStatus, outcome.serverMessageId, ms)
                prefs.recordSuccess(DeliveryEvent(now, kind))
                if (prefs.current().paused != null) {
                    // The server takes our token again (re-enabled source, or a test after fixing it).
                    prefs.setPaused(null)
                    dao.setPendingLogStatus(ForwardStatus.Queued.name, ms)
                    scheduler.uploadNow()
                }
            }
            is UploadOutcome.Retry -> {
                val delay = outcome.retryAfter?.coerceIn(MIN_RETRY_AFTER, MAX_RETRY_AFTER) ?: backoff(attempts)
                val nextAt = ms + delay.toMillis()
                dao.updateOutbox(row.id, OutboxEntity.STATE_PENDING, attempts, nextAt, outcome.detail, outcome.httpStatus)
                dao.deferPending(nextAt)
                dao.updateLog(row.logId, ForwardStatus.Retrying.name, attempts, outcome.httpStatus, outcome.detail, null, ms)
                prefs.recordFailure(FailureEvent(now, outcome.reason, outcome.httpStatus, outcome.detail))
            }
            is UploadOutcome.Rejected -> {
                dao.updateOutbox(row.id, OutboxEntity.STATE_FAILED, attempts, ms, outcome.detail, outcome.httpStatus)
                dao.updateLog(row.logId, ForwardStatus.Failed.name, attempts, outcome.httpStatus, outcome.detail, null, ms)
                prefs.recordFailure(FailureEvent(now, outcome.reason, outcome.httpStatus, outcome.detail))
            }
            is UploadOutcome.Paused -> {
                dao.updateOutbox(row.id, OutboxEntity.STATE_PENDING, attempts, ms, outcome.detail, outcome.httpStatus)
                prefs.setPaused(outcome.reason)
                val reason = when (outcome.reason) {
                    PauseReason.Unauthorized -> FailureReason.Unauthorized
                    PauseReason.SourceDisabled -> FailureReason.SourceDisabled
                }
                prefs.recordFailure(FailureEvent(now, reason, outcome.httpStatus, outcome.detail))
                dao.updateLog(row.logId, ForwardStatus.Paused.name, attempts, outcome.httpStatus, outcome.detail, null, ms)
                dao.setPendingLogStatus(ForwardStatus.Paused.name, ms)
            }
        }
    }

    private suspend fun scheduleNext() {
        if (prefs.current().paused != null) return
        val next = dao.nextPendingAt() ?: return
        scheduler.uploadAfter(Duration.ofMillis((next - clock.millis()).coerceAtLeast(0)))
    }

    companion object {
        private const val BATCH = 20
        private val MIN_RETRY_AFTER: Duration = Duration.ofSeconds(1)
        private val MAX_RETRY_AFTER: Duration = Duration.ofHours(6)
        private val FIRST_BACKOFF: Duration = Duration.ofSeconds(30)
        private val MAX_BACKOFF: Duration = Duration.ofMinutes(30)

        /** 30 s, 60 s, 2 min … capped at 30 min. */
        fun backoff(attempts: Int): Duration {
            val shift = (attempts - 1).coerceIn(0, 10)
            val delay = FIRST_BACKOFF.multipliedBy(1L shl shift)
            return if (delay > MAX_BACKOFF) MAX_BACKOFF else delay
        }
    }
}
