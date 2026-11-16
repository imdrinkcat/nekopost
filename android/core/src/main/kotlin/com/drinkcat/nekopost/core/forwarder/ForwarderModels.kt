package com.drinkcat.nekopost.core.forwarder

import java.time.Duration
import java.time.Instant

/*
 * Forwarder public API: plain Kotlin data only (no Android or Compose types),
 * so :forwarder can implement it and :app can render it without either
 * depending on the other. See android/CONTRACT.md.
 */

/** The binding to one server source, created from an EnrollmentLink. The token is never exposed. */
data class Enrollment(
    /** Normalized base URL, e.g. https://osaka.drinkcat.com */
    val serverUrl: String,
    val sourceId: String,
    val sourceName: String,
    val enrolledAt: Instant,
) {
    val serverHost: String get() = serverUrl.substringAfter("://")
}

/**
 * Permission / system-setting status. Detected by the UI (it owns the Activity
 * needed to check and request them) and reported via
 * [ForwarderController.reportPermissions]; the forwarder only reads it.
 */
data class PermissionStatus(
    val receiveSms: Boolean = false,
    val readSms: Boolean = false,
    val notificationListener: Boolean = false,
    val postNotifications: Boolean = false,
    val ignoringBatteryOptimizations: Boolean = false,
) {
    /** What real-time SMS forwarding needs at minimum. */
    val smsReady: Boolean get() = receiveSms

    val allGranted: Boolean
        get() = receiveSms && readSms && notificationListener && postNotifications && ignoringBatteryOptimizations

    companion object {
        val None = PermissionStatus()
        val All = PermissionStatus(true, true, true, true, true)
    }
}

/** Local outbox counters. */
data class QueueStats(
    /** Waiting to be sent or retried. */
    val pending: Int = 0,
    /** Rejected permanently (413/422), kept 7 days for inspection. */
    val failed: Int = 0,
    /** Creation time of the oldest pending item, if any. */
    val oldestPendingAt: Instant? = null,
) {
    val isEmpty: Boolean get() = pending == 0 && failed == 0
}

/** Last time the server accepted an item (202 or duplicate 200). */
data class DeliveryEvent(
    val at: Instant,
    val kind: ForwardKind,
)

/** Last failed upload attempt. */
data class FailureEvent(
    val at: Instant,
    val reason: FailureReason,
    /** HTTP status if the server answered, null for network errors. */
    val httpStatus: Int? = null,
    /** Short diagnostic text (server error code or exception class); never message content. */
    val detail: String? = null,
)

enum class FailureReason {
    /** No connectivity, DNS, TLS handshake, timeout. */
    Network,

    /** Certificate pinning rejected the server certificate. */
    CertificatePinning,

    /** 401: token unknown or revoked — forwarding paused, re-enroll needed. */
    Unauthorized,

    /** 403 source_disabled — forwarding paused. */
    SourceDisabled,

    /** 413 / 422 / 415: the item can never succeed. */
    Rejected,

    /** 429 / 503: retried after Retry-After. */
    RateLimited,

    /** Other 5xx. */
    Server,
}

/** Why forwarding is paused (nothing is dropped; the queue is kept). */
enum class PauseReason { Unauthorized, SourceDisabled }

/** Complete forwarder state for the UI. */
data class ForwarderState(
    /** null = not enrolled. */
    val enrollment: Enrollment? = null,
    val smsEnabled: Boolean = false,
    /** Master switch for notification forwarding; [notificationApps] lists the selected packages. */
    val notificationsEnabled: Boolean = false,
    val notificationApps: Set<String> = emptySet(),
    val permissions: PermissionStatus = PermissionStatus.None,
    val queue: QueueStats = QueueStats(),
    val lastSuccess: DeliveryEvent? = null,
    val lastFailure: FailureEvent? = null,
    val paused: PauseReason? = null,
) {
    val isEnrolled: Boolean get() = enrollment != null

    companion object {
        val Initial = ForwarderState()
    }
}

/** One-word summary of [ForwarderState] for headers and status pills. */
enum class ForwarderHealth {
    /** No enrollment yet. */
    NotEnrolled,

    /** Enrolled but neither SMS nor any notification app is being forwarded. */
    Idle,

    /** Forwarding is on but a required permission is missing. */
    NeedsPermission,

    /** Server refused the token or the source is disabled. */
    Paused,

    /** Items are waiting (offline or retrying). */
    Backlog,

    /** Everything forwarded. */
    Healthy,
}

fun ForwarderState.health(): ForwarderHealth = when {
    enrollment == null -> ForwarderHealth.NotEnrolled
    paused != null -> ForwarderHealth.Paused
    !smsEnabled && !(notificationsEnabled && notificationApps.isNotEmpty()) -> ForwarderHealth.Idle
    (smsEnabled && !permissions.smsReady) ||
        (notificationsEnabled && notificationApps.isNotEmpty() && !permissions.notificationListener) ->
        ForwarderHealth.NeedsPermission
    queue.pending > 0 || queue.failed > 0 -> ForwarderHealth.Backlog
    else -> ForwarderHealth.Healthy
}

enum class ForwardKind { Sms, Notification, Test }

/** Status of one forwarded item in the local log. */
enum class ForwardStatus {
    Queued,
    Sending,

    /** Network error / 429 / 5xx; will retry with backoff or after Retry-After. */
    Retrying,

    /** Server answered 202. */
    Accepted,

    /** Server answered 200 duplicate: it already had this item. */
    Duplicate,

    /** 413 / 415 / 422: never retried. */
    Failed,

    /** Forwarding is paused (401 / 403); the item stays queued. */
    Paused,

    /** Dropped by a local rule (sender/keyword/app filter); never uploaded. */
    Skipped,
}

/**
 * One row of the forward log (latest 50). Metadata only: no message body ever
 * appears here.
 */
data class ForwardLogItem(
    val id: Long,
    val kind: ForwardKind,
    /** SMS sender address, "<app label>" for notifications, or the test title. */
    val title: String,
    /** Package name for notifications. */
    val sourcePackage: String? = null,
    val createdAt: Instant,
    val status: ForwardStatus,
    val attempts: Int = 0,
    val lastHttpStatus: Int? = null,
    /** Short diagnostic text, no content. */
    val lastError: String? = null,
    /** Server message id (m_…) once accepted. */
    val serverMessageId: String? = null,
    val updatedAt: Instant = createdAt,
)

/** Whether Android 17's OTP protection delays verification-code SMS on this device (design §8.4). */
enum class OtpVerdict {
    /** No verification-code SMS in the window yet. */
    NoData,

    /** Every code SMS arrived through the real-time broadcast. */
    NoDelay,

    /** Some code SMS were only found by the reconciler: the system held them back. */
    DelayDetected,
}

/** Verification-code SMS arrival statistics over the last [windowDays] days. */
data class OtpHealth(
    val windowDays: Int = 7,
    /** Code SMS delivered by the SMS_RECEIVED broadcast. */
    val realtimeCount: Int = 0,
    /** Code SMS first discovered by the reconciler (inbox scan). */
    val reconciledCount: Int = 0,
    /** Median broadcast delay (receive time − SMSC timestamp), if measured. */
    val medianDelay: Duration? = null,
    /** Largest observed delay. */
    val maxDelay: Duration? = null,
    val lastCodeAt: Instant? = null,
) {
    val verdict: OtpVerdict
        get() = when {
            realtimeCount + reconciledCount == 0 -> OtpVerdict.NoData
            reconciledCount > 0 -> OtpVerdict.DelayDetected
            else -> OtpVerdict.NoDelay
        }

    companion object {
        val Empty = OtpHealth()
    }
}

/** Result of [ForwarderController.enroll]. */
sealed interface EnrollResult {
    data class Success(val enrollment: Enrollment) : EnrollResult
    data class Failure(val reason: EnrollFailure, val httpStatus: Int? = null) : EnrollResult
}

enum class EnrollFailure {
    /** Server unreachable. */
    Network,

    /** Certificate pinning failed. */
    CertificatePinning,

    /** The server rejected the token (401) — the QR code is stale or rotated. */
    Unauthorized,

    /** 403 source_disabled. */
    SourceDisabled,

    /** Any other unexpected answer. */
    Server,

    /** Already enrolled: unenroll first. */
    AlreadyEnrolled,
}

/** Result of [ForwarderController.sendTest]. */
sealed interface SendTestResult {
    /** The server accepted the test message ([serverMessageId] = m_…). */
    data class Accepted(val serverMessageId: String?, val duplicate: Boolean = false) : SendTestResult

    /** Could not send right now; it stays queued and is retried. */
    data class Queued(val reason: FailureReason) : SendTestResult

    data class Rejected(val reason: FailureReason, val httpStatus: Int? = null) : SendTestResult

    data object NotEnrolled : SendTestResult
}

/** Packages that can never be selected for notification forwarding (would loop back via Telegram). */
object NotificationAppPolicy {
    val blockedPackages: Set<String> = setOf(
        "com.drinkcat.nekopost",
        "org.telegram.messenger",
        "org.telegram.messenger.web",
        "org.telegram.messenger.beta",
        "org.telegram.plus",
        "org.thunderdog.challegram",
        "nekox.messenger",
        "tw.nekomimi.nekogram",
        "com.exteragram.messenger",
        "org.telegram.mdgram",
    )

    fun isSelectable(packageName: String): Boolean = packageName !in blockedPackages
}
