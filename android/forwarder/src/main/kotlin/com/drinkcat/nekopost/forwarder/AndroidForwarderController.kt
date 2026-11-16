package com.drinkcat.nekopost.forwarder

import android.content.Context
import android.util.Log
import com.drinkcat.nekopost.core.enroll.EnrollmentLink
import com.drinkcat.nekopost.core.forwarder.EnrollFailure
import com.drinkcat.nekopost.core.forwarder.EnrollResult
import com.drinkcat.nekopost.core.forwarder.Enrollment
import com.drinkcat.nekopost.core.forwarder.FailureReason
import com.drinkcat.nekopost.core.forwarder.ForwardKind
import com.drinkcat.nekopost.core.forwarder.ForwardLogItem
import com.drinkcat.nekopost.core.forwarder.ForwarderController
import com.drinkcat.nekopost.core.forwarder.ForwarderState
import com.drinkcat.nekopost.core.forwarder.ForwardStatus
import com.drinkcat.nekopost.core.forwarder.MessagePriority
import com.drinkcat.nekopost.core.forwarder.NotificationRule
import com.drinkcat.nekopost.core.forwarder.OtpHealth
import com.drinkcat.nekopost.core.forwarder.PauseReason
import com.drinkcat.nekopost.core.forwarder.PermissionStatus
import com.drinkcat.nekopost.core.forwarder.QueueStats
import com.drinkcat.nekopost.core.forwarder.SendTestResult
import com.drinkcat.nekopost.core.rules.SmsSenderRule
import com.drinkcat.nekopost.forwarder.crypto.IdempotencyKeys
import com.drinkcat.nekopost.forwarder.outbox.NewItem
import com.drinkcat.nekopost.forwarder.outbox.OutgoingMessage
import com.drinkcat.nekopost.forwarder.outbox.toItem
import com.drinkcat.nekopost.forwarder.sms.OtpStats
import com.drinkcat.nekopost.forwarder.upload.ApiClient
import com.drinkcat.nekopost.forwarder.upload.ProbeOutcome
import com.drinkcat.nekopost.forwarder.upload.UploadOutcome
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.time.Instant
import java.time.format.DateTimeFormatter

/**
 * The real [ForwarderController] (CONTRACT.md §1): Room outbox with
 * Tink-sealed payloads, SMS receiver + inbox reconciler, notification
 * listener, WorkManager/OkHttp uploader with ISRG root pinning.
 *
 * @param context application context
 * @param scope application-lifetime scope; setters persist on it
 */
class AndroidForwarderController internal constructor(
    private val graph: ForwarderGraph,
    private val scope: CoroutineScope,
) : ForwarderController {

    constructor(context: Context, scope: CoroutineScope) : this(ForwarderGraph.get(context), scope)

    private val permissions = MutableStateFlow(PermissionStatus.None)
    private val dao = graph.db.dao()

    override val state: StateFlow<ForwarderState> =
        combine(graph.prefs.settings, dao.queueCounts(), permissions) { s, q, p ->
            ForwarderState(
                enrollment = s.enrollment,
                smsEnabled = s.smsEnabled,
                notificationsEnabled = s.notificationsEnabled,
                notificationApps = s.notificationApps,
                permissions = p,
                queue = QueueStats(q.pending, q.failed, q.oldestPendingAt?.let(Instant::ofEpochMilli)),
                lastSuccess = s.lastSuccess,
                lastFailure = s.lastFailure,
                paused = s.paused,
                notificationRules = s.notificationRules,
                smsBacklog = s.smsBacklog,
                smsSenderRules = s.smsSenderRules,
            )
        }.stateIn(scope, SharingStarted.Eagerly, ForwarderState.Initial)

    init {
        // App start: flush, scan the inbox and apply retention (design §8.7).
        background {
            if (graph.prefs.current().enrollment != null) {
                graph.scheduler.ensurePeriodic()
                graph.scheduler.reconcileNow()
                graph.scheduler.uploadNow()
                graph.outbox.prune()
            }
        }
    }

    override suspend fun enroll(link: EnrollmentLink): EnrollResult = withContext(Dispatchers.IO) {
        graph.mutations.withLock { enrollLocked(link) }
    }

    private suspend fun enrollLocked(link: EnrollmentLink): EnrollResult {
        if (graph.prefs.current().enrollment != null) return EnrollResult.Failure(EnrollFailure.AlreadyEnrolled)
        val url = link.serverUrl.toHttpUrlOrNull() ?: return EnrollResult.Failure(EnrollFailure.Server)
        val probe = ApiClient(url, link.token, graph.http.forServer(url), graph.clock::instant).probe()
        if (probe is ProbeOutcome.Failed) return EnrollResult.Failure(probe.reason, probe.httpStatus)

        val now = graph.clock.instant()
        graph.secrets.setToken(link.token)
        graph.secrets.deviceSecret()
        val enrollment = Enrollment(
            serverUrl = link.serverUrl,
            sourceId = link.sourceId,
            sourceName = link.displayName,
            enrolledAt = now,
        )
        // Reconciler start point: the current inbox end. Never upload old SMS.
        graph.prefs.setEnrollment(enrollment, graph.inbox.maxId(), now)
        graph.scheduler.ensurePeriodic()
        return EnrollResult.Success(enrollment)
    }

    override fun unenroll() = background {
        graph.mutations.withLock { graph.wipe() }
    }

    override fun setSmsEnabled(enabled: Boolean) = background {
        graph.mutations.withLock {
            val start = if (enabled) graph.inbox.maxId() else null
            graph.prefs.setSmsEnabled(enabled, start, graph.clock.instant())
        }
    }

    override fun setNotificationsEnabled(enabled: Boolean) = background {
        graph.mutations.withLock { graph.prefs.setNotificationsEnabled(enabled) }
    }

    override fun setNotificationApps(packages: Set<String>) = background {
        graph.mutations.withLock { graph.prefs.setNotificationApps(packages) }
    }

    override fun setNotificationRule(packageName: String, rule: NotificationRule?) = background {
        graph.mutations.withLock { graph.prefs.setNotificationRule(packageName, rule) }
    }

    override fun resolveSmsBacklog(upload: Boolean) = background {
        graph.mutations.withLock {
            if (upload) graph.reconciler.run(confirmBacklog = true) else graph.reconciler.skipBacklog()
        }
    }

    override fun setSmsSenderRules(rules: List<SmsSenderRule>) = background {
        graph.mutations.withLock { graph.prefs.setSmsSenderRules(rules) }
    }

    override fun reportPermissions(status: PermissionStatus) {
        val before = permissions.value
        permissions.value = status
        if (status.readSms && !before.readSms) {
            // READ_SMS just granted: pin the start point to the inbox end now.
            background {
                graph.inbox.maxId()?.let { graph.prefs.initSmsStartIfUnset(it) }
            }
        }
    }

    override fun observeLog(): Flow<List<ForwardLogItem>> =
        dao.logFlow(LOG_SIZE).map { rows -> rows.map { it.toItem() } }

    override fun otpHealth(): Flow<OtpHealth> =
        dao.codeEvents().map { OtpStats.compute(it, graph.clock.instant()) }

    override suspend fun sendTest(): SendTestResult = withContext(Dispatchers.IO) {
        if (graph.prefs.current().enrollment == null) return@withContext SendTestResult.NotEnrolled
        val now = graph.clock.instant()
        val time = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(graph.clock.zone).format(now)
        val message = OutgoingMessage(
            type = "test",
            title = "猫邮局测试消息",
            body = "这是一条来自 Nekopost 安卓端的测试消息（$time）。收到即表示手机到服务器的转发正常。",
            priority = MessagePriority.Normal,
            occurredAt = now,
        )
        val id = graph.outbox.enqueue(NewItem(ForwardKind.Test, TEST_TITLE, null, IdempotencyKeys.random(), message))
            ?: return@withContext SendTestResult.Queued(FailureReason.Network)
        when (val outcome = graph.uploader.sendOne(id)) {
            is UploadOutcome.Accepted -> SendTestResult.Accepted(outcome.serverMessageId, outcome.duplicate)
            is UploadOutcome.Retry -> SendTestResult.Queued(outcome.reason)
            is UploadOutcome.Rejected -> SendTestResult.Rejected(outcome.reason, outcome.httpStatus)
            is UploadOutcome.Paused -> SendTestResult.Rejected(
                if (outcome.reason == PauseReason.Unauthorized) FailureReason.Unauthorized else FailureReason.SourceDisabled,
                outcome.httpStatus,
            )
            null -> SendTestResult.Queued(FailureReason.Network)
        }
    }

    override fun retryNow() = background {
        val now = graph.clock.millis()
        graph.prefs.setPaused(null)
        dao.makeAllDue(now)
        dao.setPendingLogStatus(ForwardStatus.Queued.name, now)
        graph.scheduler.uploadNow()
    }

    override fun clearFailed() = background {
        dao.deleteFailed()
    }

    private fun background(block: suspend () -> Unit) {
        scope.launch(Dispatchers.IO) {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "forwarder update failed: ${e.javaClass.simpleName}")
            }
        }
    }

    private companion object {
        const val TAG = "NekoForwarder"
        const val LOG_SIZE = 50
        const val TEST_TITLE = "测试消息"
    }
}
