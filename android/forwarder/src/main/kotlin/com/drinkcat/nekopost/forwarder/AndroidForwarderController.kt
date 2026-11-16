package com.drinkcat.nekopost.forwarder

import android.content.Context
import com.drinkcat.nekopost.core.enroll.EnrollmentLink
import com.drinkcat.nekopost.core.forwarder.EnrollFailure
import com.drinkcat.nekopost.core.forwarder.EnrollResult
import com.drinkcat.nekopost.core.forwarder.ForwardLogItem
import com.drinkcat.nekopost.core.forwarder.ForwarderController
import com.drinkcat.nekopost.core.forwarder.ForwarderState
import com.drinkcat.nekopost.core.forwarder.NotificationAppPolicy
import com.drinkcat.nekopost.core.forwarder.OtpHealth
import com.drinkcat.nekopost.core.forwarder.PermissionStatus
import com.drinkcat.nekopost.core.forwarder.SendTestResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.update

/**
 * The real [ForwarderController]. M0 skeleton: state lives in memory only and
 * nothing is sent. M1 replaces the internals with the Room outbox (Tink AEAD
 * payloads), SmsReceiver/SmsReconciler, NotificationListener and the
 * WorkManager/OkHttp uploader, keeping this class name and constructor.
 *
 * @param context application context
 * @param scope application-lifetime scope for background persistence
 */
class AndroidForwarderController(
    @Suppress("unused") private val context: Context,
    @Suppress("unused") private val scope: CoroutineScope,
) : ForwarderController {

    private val _state = MutableStateFlow(ForwarderState.Initial)
    override val state: StateFlow<ForwarderState> = _state.asStateFlow()

    override suspend fun enroll(link: EnrollmentLink): EnrollResult =
        EnrollResult.Failure(EnrollFailure.Server)

    override fun unenroll() {
        _state.update { ForwarderState.Initial.copy(permissions = it.permissions) }
    }

    override fun setSmsEnabled(enabled: Boolean) = _state.update { it.copy(smsEnabled = enabled) }

    override fun setNotificationsEnabled(enabled: Boolean) = _state.update { it.copy(notificationsEnabled = enabled) }

    override fun setNotificationApps(packages: Set<String>) =
        _state.update { it.copy(notificationApps = packages.filter(NotificationAppPolicy::isSelectable).toSet()) }

    override fun reportPermissions(status: PermissionStatus) = _state.update { it.copy(permissions = status) }

    override fun observeLog(): Flow<List<ForwardLogItem>> = flowOf(emptyList())

    override fun otpHealth(): Flow<OtpHealth> = flowOf(OtpHealth.Empty)

    override suspend fun sendTest(): SendTestResult = SendTestResult.NotEnrolled

    override fun retryNow() = Unit

    override fun clearFailed() = Unit
}
