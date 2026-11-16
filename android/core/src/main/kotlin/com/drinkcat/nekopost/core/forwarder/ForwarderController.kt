package com.drinkcat.nekopost.core.forwarder

import com.drinkcat.nekopost.core.enroll.EnrollmentLink
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * The only thing the UI knows about forwarding. Implemented by
 * `com.drinkcat.nekopost.forwarder.AndroidForwarderController` (:forwarder)
 * and by [FakeForwarderController] for previews and screenshot tests.
 *
 * Threading: every member is main-safe. Non-suspending setters return
 * immediately and persist asynchronously; their effect shows up in [state].
 */
interface ForwarderController {
    /** Current state; never blocks, always has a value. */
    val state: StateFlow<ForwarderState>

    /**
     * Binds this device to the source in [link]: verifies the token with an
     * authenticated request that creates no message (GET /v1/messages/<probe id>:
     * 404 = token valid, 401 = invalid, 403 = source disabled), stores it
     * encrypted, and on success sets the SMS reconciler start point to "now" so no old SMS
     * is ever uploaded. Fails with [EnrollFailure.AlreadyEnrolled] if enrolled.
     */
    suspend fun enroll(link: EnrollmentLink): EnrollResult

    /**
     * Forgets the enrollment: deletes the token, the outbox, the log and all
     * keys. Forwarding stops immediately. Cannot be undone.
     */
    fun unenroll()

    /** Turns real-time SMS forwarding (receiver + reconciler) on or off. */
    fun setSmsEnabled(enabled: Boolean)

    /** Master switch for notification forwarding. */
    fun setNotificationsEnabled(enabled: Boolean)

    /**
     * Replaces the set of packages whose notifications are forwarded.
     * Packages rejected by [NotificationAppPolicy] are silently dropped.
     */
    fun setNotificationApps(packages: Set<String>)

    /**
     * Sets the rule for one package ([NotificationRule.Default] when [rule] is
     * null). Rules for packages that are not selected are kept but unused.
     */
    fun setNotificationRule(packageName: String, rule: NotificationRule?)

    /**
     * Answers [ForwarderState.smsBacklog]: [upload] = true sends the held SMS
     * ("补交 N 条短信"), false skips them for good ("全部跳过").
     */
    fun resolveSmsBacklog(upload: Boolean)

    /** Called by the UI whenever it (re)checks permissions, e.g. in onResume. */
    fun reportPermissions(status: PermissionStatus)

    /** Latest 50 log rows, newest first. Metadata only. */
    fun observeLog(): Flow<List<ForwardLogItem>>

    /** Verification-code SMS delay statistics (Android 17 OTP protection check). */
    fun otpHealth(): Flow<OtpHealth>

    /**
     * Queues a `type:"test"` message and tries to send it right away. Also
     * works while paused, so it doubles as a credential check (success clears
     * the pause).
     */
    suspend fun sendTest(): SendTestResult

    /** Flushes the outbox now (user pressed "立即重试"); also resumes after a pause was fixed. */
    fun retryNow()

    /** Deletes permanently failed items from the outbox. */
    fun clearFailed()
}
