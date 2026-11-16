package com.drinkcat.nekopost.core.forwarder

import com.drinkcat.nekopost.core.enroll.EnrollmentLink
import com.drinkcat.nekopost.core.rules.SmsSenderRule
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.time.Duration
import java.time.Instant

/**
 * In-memory [ForwarderController] for previews, screenshot tests and UI
 * development. Deterministic: all timestamps derive from [FakeForwarderData.Now].
 */
class FakeForwarderController(
    initial: ForwarderState = FakeForwarderData.enrolledState,
    log: List<ForwardLogItem> = FakeForwarderData.log,
    otp: OtpHealth = FakeForwarderData.otpHealthy,
) : ForwarderController {
    private val _state = MutableStateFlow(initial)
    private val _log = MutableStateFlow(log)
    private val _otp = MutableStateFlow(otp)
    private var nextId = (log.maxOfOrNull { it.id } ?: 0L) + 1

    override val state: StateFlow<ForwarderState> = _state.asStateFlow()

    override suspend fun enroll(link: EnrollmentLink): EnrollResult {
        if (_state.value.isEnrolled) return EnrollResult.Failure(EnrollFailure.AlreadyEnrolled)
        val enrollment = Enrollment(link.serverUrl, link.sourceId, link.displayName, FakeForwarderData.Now)
        _state.update { it.copy(enrollment = enrollment, paused = null) }
        return EnrollResult.Success(enrollment)
    }

    override fun unenroll() {
        _state.value = ForwarderState.Initial.copy(
            permissions = _state.value.permissions,
            smsSenderRules = _state.value.smsSenderRules,
        )
        _log.value = emptyList()
    }

    override fun setSmsEnabled(enabled: Boolean) = _state.update { it.copy(smsEnabled = enabled) }

    override fun setNotificationsEnabled(enabled: Boolean) = _state.update { it.copy(notificationsEnabled = enabled) }

    override fun setNotificationApps(packages: Set<String>) =
        _state.update { it.copy(notificationApps = packages.filter(NotificationAppPolicy::isSelectable).toSet()) }

    override fun setNotificationRule(packageName: String, rule: NotificationRule?) = _state.update {
        it.copy(notificationRules = if (rule == null) it.notificationRules - packageName else it.notificationRules + (packageName to rule))
    }

    override fun resolveSmsBacklog(upload: Boolean) = _state.update { it.copy(smsBacklog = null) }

    override fun setSmsSenderRules(rules: List<SmsSenderRule>) = _state.update { it.copy(smsSenderRules = rules) }

    override fun reportPermissions(status: PermissionStatus) = _state.update { it.copy(permissions = status) }

    override fun observeLog(): Flow<List<ForwardLogItem>> = _log.asStateFlow()

    override fun otpHealth(): Flow<OtpHealth> = _otp.asStateFlow()

    override suspend fun sendTest(): SendTestResult {
        if (!_state.value.isEnrolled) return SendTestResult.NotEnrolled
        val item = ForwardLogItem(
            id = nextId++,
            kind = ForwardKind.Test,
            title = "测试消息",
            createdAt = FakeForwarderData.Now,
            status = ForwardStatus.Accepted,
            attempts = 1,
            lastHttpStatus = 202,
            serverMessageId = "m_FAKE${nextId}",
        )
        _log.update { listOf(item) + it }
        _state.update { it.copy(lastSuccess = DeliveryEvent(FakeForwarderData.Now, ForwardKind.Test)) }
        return SendTestResult.Accepted(item.serverMessageId)
    }

    override fun retryNow() = _state.update { it.copy(paused = null) }

    override fun clearFailed() = _state.update { it.copy(queue = it.queue.copy(failed = 0)) }
}

/** Fixed sample data shared by previews and screenshot tests. */
object FakeForwarderData {
    /** 2026-11-16 10:20 JST. */
    val Now: Instant = Instant.parse("2026-11-16T01:20:00Z")

    private fun ago(minutes: Long): Instant = Now.minus(Duration.ofMinutes(minutes))

    val enrollment = Enrollment(
        serverUrl = "https://osaka.drinkcat.com",
        sourceId = "fold8",
        sourceName = "Fold8 主力机",
        enrolledAt = Instant.parse("2026-11-10T03:00:00Z"),
    )

    val enrolledState = ForwarderState(
        enrollment = enrollment,
        smsEnabled = true,
        notificationsEnabled = true,
        notificationApps = setOf("jp.co.rakuten.bank", "com.sagawa.app", "jp.naver.line.android"),
        permissions = PermissionStatus.All.copy(ignoringBatteryOptimizations = false),
        queue = QueueStats(pending = 1, failed = 0, oldestPendingAt = ago(2)),
        lastSuccess = DeliveryEvent(ago(3), ForwardKind.Sms),
        lastFailure = FailureEvent(ago(2), FailureReason.Network, detail = "UnknownHostException"),
    )

    val notEnrolledState = ForwarderState(permissions = PermissionStatus.None)

    val log: List<ForwardLogItem> = listOf(
        ForwardLogItem(
            id = 6, kind = ForwardKind.Sms, title = "+81 90-1234-5678", createdAt = ago(2),
            status = ForwardStatus.Retrying, attempts = 2, lastError = "UnknownHostException",
        ),
        ForwardLogItem(
            id = 5, kind = ForwardKind.Sms, title = "CMBCHINA", createdAt = ago(3),
            status = ForwardStatus.Accepted, attempts = 1, lastHttpStatus = 202,
            serverMessageId = "m_01JB7Q4ZK3M8X2N5R6T7V8W9YA",
        ),
        ForwardLogItem(
            id = 4, kind = ForwardKind.Notification, title = "楽天銀行", sourcePackage = "jp.co.rakuten.bank",
            createdAt = ago(26), status = ForwardStatus.Accepted, attempts = 1, lastHttpStatus = 202,
            serverMessageId = "m_01JB7Q2AA9X1C4D5E6F7G8H9JK",
        ),
        ForwardLogItem(
            id = 3, kind = ForwardKind.Notification, title = "佐川急便", sourcePackage = "com.sagawa.app",
            createdAt = ago(75), status = ForwardStatus.Duplicate, attempts = 1, lastHttpStatus = 200,
            serverMessageId = "m_01JB7MZ0QW2E3R4T5Y6U7I8O9P",
        ),
        ForwardLogItem(
            id = 2, kind = ForwardKind.Notification, title = "LINE", sourcePackage = "jp.naver.line.android",
            createdAt = ago(140), status = ForwardStatus.Skipped,
        ),
        ForwardLogItem(
            id = 1, kind = ForwardKind.Test, title = "测试消息", createdAt = ago(60 * 24),
            status = ForwardStatus.Failed, attempts = 1, lastHttpStatus = 422, lastError = "invalid_field",
        ),
    )

    val otpHealthy = OtpHealth(
        windowDays = 7,
        realtimeCount = 12,
        reconciledCount = 0,
        medianDelay = Duration.ofSeconds(2),
        maxDelay = Duration.ofSeconds(9),
        lastCodeAt = ago(3),
    )

    val otpDelayed = otpHealthy.copy(realtimeCount = 9, reconciledCount = 3, maxDelay = Duration.ofMinutes(181))
}
