package com.drinkcat.nekopost.ui.setup

import androidx.compose.runtime.Immutable
import com.drinkcat.nekopost.core.enroll.EnrollmentError
import com.drinkcat.nekopost.core.enroll.EnrollmentLink
import com.drinkcat.nekopost.core.forwarder.EnrollFailure
import com.drinkcat.nekopost.core.forwarder.Enrollment
import com.drinkcat.nekopost.core.forwarder.OtpHealth
import com.drinkcat.nekopost.core.forwarder.OtpVerdict
import com.drinkcat.nekopost.core.forwarder.PermissionStatus

/** Steps of the first-run wizard (design §8.6), in order. */
enum class SetupStep {
    Welcome,
    Enroll,
    RestrictedSettings,
    SmsPermission,
    NotificationAccess,
    PostNotifications,
    Battery,
    CoverScreen,
    OtpSelfTest,
    Finish,
}

enum class StepStatus {
    /** Checked and fine (✅). */
    Done,

    /** Needs the user (⚠️). */
    NeedsAction,

    /** Recommended, can be skipped. */
    Optional,

    /** Does not apply to this device; the step is hidden. */
    NotApplicable,
}

/** Static facts about the device that change which steps apply. */
@Immutable
data class DeviceTraits(
    val sdkInt: Int,
    val isSamsung: Boolean,
    val isFoldable: Boolean,
)

/** Everything the wizard checks, gathered on each onResume. */
@Immutable
data class SetupSnapshot(
    val enrollment: Enrollment?,
    val permissions: PermissionStatus,
    val otp: OtpHealth,
    val device: DeviceTraits,
    /** Steps confirmed by hand because the system cannot report them. */
    val acknowledged: Set<SetupStep> = emptySet(),
    /** The last SMS permission request was refused without a rationale: most likely blocked as a restricted setting. */
    val smsBlocked: Boolean = false,
    /** The finish step's app-lock line: true = on, false = needs a screen lock first, null = none. */
    val appLock: Boolean? = null,
)

/** Steps that must be done for forwarding to work reliably. */
val RequiredSteps = listOf(
    SetupStep.Enroll,
    SetupStep.RestrictedSettings,
    SetupStep.SmsPermission,
    SetupStep.NotificationAccess,
    SetupStep.PostNotifications,
    SetupStep.Battery,
)

fun SetupSnapshot.statusOf(step: SetupStep): StepStatus = when (step) {
    SetupStep.Welcome -> if (step in acknowledged) StepStatus.Done else StepStatus.Optional
    SetupStep.Enroll -> if (enrollment != null) StepStatus.Done else StepStatus.NeedsAction
    // Restricted settings exist since Android 13 for sideloaded apps; once SMS or
    // notification access is granted they were evidently allowed.
    SetupStep.RestrictedSettings -> when {
        device.sdkInt < 33 -> StepStatus.NotApplicable
        permissions.receiveSms || permissions.notificationListener -> StepStatus.Done
        step in acknowledged && !smsBlocked -> StepStatus.Done
        else -> StepStatus.NeedsAction
    }
    SetupStep.SmsPermission ->
        if (permissions.receiveSms && permissions.readSms) StepStatus.Done else StepStatus.NeedsAction
    SetupStep.NotificationAccess ->
        if (permissions.notificationListener) StepStatus.Done else StepStatus.NeedsAction
    SetupStep.PostNotifications ->
        if (permissions.postNotifications) StepStatus.Done else StepStatus.NeedsAction
    SetupStep.Battery ->
        if (permissions.ignoringBatteryOptimizations) StepStatus.Done else StepStatus.NeedsAction
    SetupStep.CoverScreen -> when {
        !device.isFoldable -> StepStatus.NotApplicable
        step in acknowledged -> StepStatus.Done
        else -> StepStatus.Optional
    }
    SetupStep.OtpSelfTest -> when (otp.verdict) {
        OtpVerdict.NoDelay -> StepStatus.Done
        OtpVerdict.DelayDetected -> StepStatus.NeedsAction
        OtpVerdict.NoData -> if (step in acknowledged) StepStatus.Done else StepStatus.Optional
    }
    SetupStep.Finish -> if (pendingRequired().isEmpty()) StepStatus.Done else StepStatus.Optional
}

/** Required steps still waiting for the user. */
fun SetupSnapshot.pendingRequired(): List<SetupStep> =
    RequiredSteps.filter { statusOf(it) == StepStatus.NeedsAction }

/** Steps shown to the user, in order (not-applicable ones are hidden). */
fun SetupSnapshot.visibleSteps(): List<SetupStep> =
    SetupStep.entries.filter { statusOf(it) != StepStatus.NotApplicable }

fun SetupSnapshot.nextStep(current: SetupStep): SetupStep? {
    val steps = visibleSteps()
    val i = steps.indexOf(current)
    return if (i < 0) steps.firstOrNull { it.ordinal > current.ordinal } else steps.getOrNull(i + 1)
}

fun SetupSnapshot.previousStep(current: SetupStep): SetupStep? {
    val steps = visibleSteps()
    val i = steps.indexOf(current)
    return if (i < 0) steps.lastOrNull { it.ordinal < current.ordinal } else steps.getOrNull(i - 1)
}

/** State of the enrollment step. The link (with its token) lives only in memory. */
sealed interface EnrollUiState {
    data object Idle : EnrollUiState

    /** The scanned or pasted text is not a valid enrollment link. */
    data class Invalid(val error: EnrollmentError) : EnrollUiState

    /** The Google code scanner is not available (no Play services or module download failed). */
    data object ScannerUnavailable : EnrollUiState

    /** Parsed; waiting for the user to confirm server and source. */
    data class Confirm(val link: EnrollmentLink) : EnrollUiState

    data class Enrolling(val link: EnrollmentLink) : EnrollUiState

    data class Failed(val link: EnrollmentLink, val failure: EnrollFailure, val httpStatus: Int? = null) : EnrollUiState
}

/** Callbacks of the wizard; all default to no-ops for previews and tests. */
@Immutable
data class SetupActions(
    val onStepChange: (SetupStep) -> Unit = {},
    val onClose: () -> Unit = {},
    val onFinish: () -> Unit = {},
    val onAcknowledge: (SetupStep) -> Unit = {},
    val onScanQr: () -> Unit = {},
    val onSubmitLink: (String) -> Unit = {},
    val onPasteFromClipboard: () -> Unit = {},
    val onConfirmEnroll: () -> Unit = {},
    val onCancelEnroll: () -> Unit = {},
    val onOpenAppDetails: () -> Unit = {},
    val onRequestSms: () -> Unit = {},
    val onOpenNotificationAccess: () -> Unit = {},
    val onRequestPostNotifications: () -> Unit = {},
    val onRequestBatteryExemption: () -> Unit = {},
    val onOpenBatterySettings: () -> Unit = {},
    val onOpenDisplaySettings: () -> Unit = {},
    /** 接收消息：配对此手机 (the identity choice on the enroll step). */
    val onPairDevice: () -> Unit = {},
)
