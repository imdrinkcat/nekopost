package com.drinkcat.nekopost.forwarder.sms

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.database.Cursor
import android.provider.BaseColumns
import android.provider.Telephony
import android.util.Log
import androidx.core.content.ContextCompat
import com.drinkcat.nekopost.core.forwarder.SmsBacklog
import com.drinkcat.nekopost.core.rules.RuleAction
import com.drinkcat.nekopost.core.rules.SmsSenderRules
import com.drinkcat.nekopost.forwarder.store.ForwarderPrefs
import java.time.Clock
import java.time.Duration
import java.time.Instant

/** One row of `Telephony.Sms.Inbox`. */
internal data class InboxSms(
    val id: Long,
    val address: String,
    val body: String,
    /** When the phone received it (`date`). */
    val dateMillis: Long,
    /** SMSC timestamp (`date_sent`, 0 if the provider does not fill it). */
    val dateSentMillis: Long,
    val subId: Int,
) {
    /** The timestamp the broadcast path uses for the idempotency key. */
    val smscMillis: Long get() = if (dateSentMillis > 0) dateSentMillis else dateMillis
}

/** Read access to the SMS inbox; faked in tests. */
internal interface SmsInboxSource {
    fun canRead(): Boolean

    /** Highest `_id` in the inbox (0 if empty), null without READ_SMS. */
    fun maxId(): Long?

    /**
     * Rows with `_id > afterId`, or with `date > afterDate` when given (the
     * provider reuses the `_id` of deleted rows), ascending by `_id`, at most [limit].
     */
    fun after(afterId: Long, afterDate: Long?, limit: Int): List<InboxSms>
}

internal class ContentResolverInbox(private val context: Context) : SmsInboxSource {
    override fun canRead(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission") // checked by canRead()
    override fun maxId(): Long? {
        if (!canRead()) return null
        return query(arrayOf(BaseColumns._ID), null, null, "${BaseColumns._ID} DESC LIMIT 1") { c ->
            if (c.moveToFirst()) c.getLong(0) else 0L
        }
    }

    @SuppressLint("MissingPermission") // checked by canRead()
    override fun after(afterId: Long, afterDate: Long?, limit: Int): List<InboxSms> {
        if (!canRead()) return emptyList()
        val projection = arrayOf(
            BaseColumns._ID,
            Telephony.TextBasedSmsColumns.ADDRESS,
            Telephony.TextBasedSmsColumns.BODY,
            Telephony.TextBasedSmsColumns.DATE,
            Telephony.TextBasedSmsColumns.DATE_SENT,
            Telephony.TextBasedSmsColumns.SUBSCRIPTION_ID,
        )
        val (selection, args) = if (afterDate == null) {
            "${BaseColumns._ID} > ?" to arrayOf(afterId.toString())
        } else {
            "${BaseColumns._ID} > ? OR ${Telephony.TextBasedSmsColumns.DATE} > ?" to arrayOf(afterId.toString(), afterDate.toString())
        }
        return query(
            projection,
            selection,
            args,
            "${BaseColumns._ID} ASC LIMIT $limit",
        ) { c ->
            val rows = ArrayList<InboxSms>(c.count.coerceAtLeast(0))
            while (c.moveToNext()) {
                rows += InboxSms(
                    id = c.getLong(0),
                    address = c.getString(1) ?: "",
                    body = c.getString(2) ?: "",
                    dateMillis = c.getLong(3),
                    dateSentMillis = c.getLong(4),
                    subId = if (c.isNull(5)) -1 else c.getInt(5),
                )
            }
            rows
        } ?: emptyList()
    }

    private fun <T> query(
        projection: Array<String>,
        selection: String?,
        args: Array<String>?,
        order: String,
        read: (Cursor) -> T,
    ): T? = try {
        context.contentResolver.query(Telephony.Sms.Inbox.CONTENT_URI, projection, selection, args, order)?.use(read)
    } catch (e: SecurityException) {
        Log.w(TAG, "inbox query denied")
        null
    } catch (e: RuntimeException) {
        Log.w(TAG, "inbox query failed: ${e.javaClass.simpleName}")
        null
    }

    private companion object {
        const val TAG = "NekoInbox"
    }
}

internal sealed interface ReconcileResult {
    data object NotEnabled : ReconcileResult
    data object NoPermission : ReconcileResult

    /** First scan: remembered the current inbox end, uploaded nothing. */
    data class StartPointSet(val lastSeenId: Long) : ReconcileResult
    data class Done(val queued: Int, val lastSeenId: Long) : ReconcileResult

    /** More than [SmsBacklog.Threshold] unsent SMS: waiting for the user. */
    data class NeedsConfirmation(val count: Int) : ReconcileResult
}

/**
 * Catches SMS the broadcast missed (app killed, before first unlock, OTP
 * held back by the system) by scanning the inbox (design §8.4).
 *
 * Never uploads history: the start point is the inbox's MAX(_id) when SMS
 * forwarding or the enrollment started, and every scan also ignores SMS
 * received before max(now − 72 h, enabled_at). More than 50 candidates in one
 * scan are held until the user confirms.
 *
 * The provider's `_id` has no AUTOINCREMENT: after the newest SMS is deleted,
 * the next one gets the same `_id`. So besides `_id > last seen` the scan
 * also takes rows received after the newest one already handled; the
 * one-to-one matching in [SmsIngest] absorbs any overlap.
 */
internal class SmsReconciler(
    private val prefs: ForwarderPrefs,
    private val inbox: SmsInboxSource,
    private val ingest: SmsIngest,
    private val clock: Clock,
) {
    /**
     * @param confirmBacklog true when the user chose "补交" — uploads even more
     *   than [SmsBacklog.Threshold] SMS (still within the lookback)
     */
    suspend fun run(confirmBacklog: Boolean = false): ReconcileResult {
        val settings = prefs.current()
        if (settings.enrollment == null || !settings.smsEnabled) return ReconcileResult.NotEnabled
        if (!inbox.canRead()) return ReconcileResult.NoPermission
        val now = clock.instant()

        val lastSeen = settings.smsLastSeenId
        if (lastSeen == null) {
            val start = inbox.maxId() ?: return ReconcileResult.NoPermission
            prefs.initSmsStartIfUnset(start, now)
            return ReconcileResult.StartPointSet(start)
        }

        val enabledAt = settings.smsEnabledAt ?: now
        val lookbackStart = maxOf(now.minus(LOOKBACK), enabledAt).toEpochMilli()
        val rows = inbox.after(lastSeen, settings.smsLastSeenDate, PAGE)
        if (rows.isEmpty()) {
            if (settings.smsBacklog != null) prefs.setBacklog(null)
            return ReconcileResult.Done(0, lastSeen)
        }
        val newLastSeen = maxOf(lastSeen, rows.maxOf { it.id })
        // A clock that jumped ahead must not hide later SMS: never past now.
        val newLastSeenDate = minOf(rows.maxOf { it.dateMillis }, now.toEpochMilli())
        val candidates = rows.filter { it.dateMillis >= lookbackStart && it.address.isNotEmpty() }
        val uncaptured = ingest.uncaptured(candidates)

        // Only SMS the sender rules would forward count towards the backlog;
        // skipped ones still go through ingest so they are logged as Skipped.
        val toForward = uncaptured.filter { SmsSenderRules.decide(settings.smsSenderRules, it.address) == RuleAction.Forward }
        if (toForward.size > SmsBacklog.Threshold && !confirmBacklog) {
            prefs.setBacklog(
                SmsBacklog(
                    count = toForward.size,
                    oldestAt = Instant.ofEpochMilli(toForward.minOf { it.dateMillis }),
                    detectedAt = now,
                ),
            )
            return ReconcileResult.NeedsConfirmation(toForward.size)
        }

        // All candidates, so inbox rows of SMS the broadcast captured get matched too.
        val queued = ingest.onReconciled(candidates, now)
        prefs.setSmsLastSeen(newLastSeen, newLastSeenDate)
        if (settings.smsBacklog != null) prefs.setBacklog(null)
        return ReconcileResult.Done(queued, newLastSeen)
    }

    /** "全部跳过": move the start point to the inbox end without uploading anything. */
    suspend fun skipBacklog() {
        inbox.maxId()?.let { prefs.setSmsLastSeen(it, clock.millis()) }
        prefs.setBacklog(null)
    }

    companion object {
        val LOOKBACK: Duration = Duration.ofHours(72)
        private const val PAGE = 1000
    }
}
