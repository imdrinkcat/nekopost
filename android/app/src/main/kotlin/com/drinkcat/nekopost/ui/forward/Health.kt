package com.drinkcat.nekopost.ui.forward

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.drinkcat.nekopost.R
import com.drinkcat.nekopost.core.forwarder.ForwarderHealth
import com.drinkcat.nekopost.core.forwarder.ForwarderState
import com.drinkcat.nekopost.core.forwarder.OtpHealth
import com.drinkcat.nekopost.core.forwarder.OtpVerdict
import com.drinkcat.nekopost.core.forwarder.PauseReason
import com.drinkcat.nekopost.core.forwarder.health
import com.drinkcat.nekopost.core.ui.component.RingSegment
import com.drinkcat.nekopost.core.ui.theme.StatusTone

/** The six components of the forwarding path, each with a tone (design §10.4 ④). */
enum class HealthPart { Server, Sms, Notifications, Queue, Otp, Battery }

fun ForwarderState.toneOf(part: HealthPart, otp: OtpHealth): StatusTone = when (part) {
    HealthPart.Server -> when {
        enrollment == null -> StatusTone.Neutral
        paused != null -> StatusTone.Error
        else -> StatusTone.Success
    }
    HealthPart.Sms -> when {
        !smsEnabled -> StatusTone.Neutral
        !permissions.smsReady -> StatusTone.Warning
        else -> StatusTone.Success
    }
    HealthPart.Notifications -> when {
        !notificationsEnabled || notificationApps.isEmpty() -> StatusTone.Neutral
        !permissions.notificationListener -> StatusTone.Warning
        else -> StatusTone.Success
    }
    HealthPart.Queue -> when {
        enrollment == null -> StatusTone.Neutral
        queue.failed > 0 -> StatusTone.Error
        queue.pending > 0 -> StatusTone.Warning
        else -> StatusTone.Success
    }
    HealthPart.Otp -> when (otp.verdict) {
        OtpVerdict.NoData -> StatusTone.Neutral
        OtpVerdict.NoDelay -> StatusTone.Success
        OtpVerdict.DelayDetected -> StatusTone.Warning
    }
    HealthPart.Battery -> if (permissions.ignoringBatteryOptimizations) StatusTone.Success else StatusTone.Warning
}

@Composable
fun healthPartLabel(part: HealthPart): String = stringResource(
    when (part) {
        HealthPart.Server -> R.string.part_server
        HealthPart.Sms -> R.string.part_sms
        HealthPart.Notifications -> R.string.part_notifications
        HealthPart.Queue -> R.string.part_queue
        HealthPart.Otp -> R.string.part_otp
        HealthPart.Battery -> R.string.part_battery
    },
)

@Composable
fun healthPartState(part: HealthPart, f: ForwarderState, otp: OtpHealth): String = when (part) {
    HealthPart.Server -> when {
        f.enrollment == null -> stringResource(R.string.state_not_enrolled)
        f.paused == PauseReason.SourceDisabled -> stringResource(R.string.state_source_disabled)
        f.paused != null -> stringResource(R.string.state_credential_rejected)
        else -> stringResource(R.string.state_connected)
    }
    HealthPart.Sms -> when {
        !f.smsEnabled -> stringResource(R.string.state_off)
        !f.permissions.smsReady -> stringResource(R.string.state_missing_permission)
        else -> stringResource(R.string.state_realtime)
    }
    HealthPart.Notifications -> when {
        !f.notificationsEnabled -> stringResource(R.string.state_off)
        f.notificationApps.isEmpty() -> stringResource(R.string.state_no_apps)
        !f.permissions.notificationListener -> stringResource(R.string.state_listener_missing)
        else -> stringResource(R.string.state_apps_count, f.notificationApps.size)
    }
    HealthPart.Queue -> when {
        f.queue.failed > 0 -> stringResource(R.string.state_queue_failed, f.queue.pending, f.queue.failed)
        f.queue.pending > 0 -> stringResource(R.string.state_queue_pending, f.queue.pending)
        else -> stringResource(R.string.state_queue_empty)
    }
    HealthPart.Otp -> when (otp.verdict) {
        OtpVerdict.NoData -> stringResource(R.string.otp_no_data)
        OtpVerdict.NoDelay -> stringResource(R.string.otp_no_delay)
        OtpVerdict.DelayDetected -> stringResource(R.string.otp_delayed)
    }
    HealthPart.Battery -> stringResource(
        if (f.permissions.ignoringBatteryOptimizations) R.string.state_battery_ok else R.string.state_battery_restricted,
    )
}

@Composable
fun healthSegments(f: ForwarderState, otp: OtpHealth): List<RingSegment> = HealthPart.entries.map { part ->
    RingSegment(label = healthPartLabel(part), tone = f.toneOf(part, otp), state = healthPartState(part, f, otp))
}

data class HealthText(val title: Int, val tone: StatusTone)

fun healthText(h: ForwarderHealth) = when (h) {
    ForwarderHealth.NotEnrolled -> HealthText(R.string.health_not_enrolled, StatusTone.Neutral)
    ForwarderHealth.Idle -> HealthText(R.string.health_idle, StatusTone.Neutral)
    ForwarderHealth.NeedsPermission -> HealthText(R.string.health_needs_permission, StatusTone.Warning)
    ForwarderHealth.Paused -> HealthText(R.string.health_paused, StatusTone.Error)
    ForwarderHealth.Backlog -> HealthText(R.string.health_backlog, StatusTone.Warning)
    ForwarderHealth.Healthy -> HealthText(R.string.health_healthy, StatusTone.Success)
}

@Composable
fun healthDetail(f: ForwarderState): String = when (f.health()) {
    ForwarderHealth.NotEnrolled -> stringResource(R.string.health_not_enrolled_detail)
    ForwarderHealth.Idle -> stringResource(R.string.health_idle_detail)
    ForwarderHealth.NeedsPermission -> stringResource(R.string.health_needs_permission_detail)
    ForwarderHealth.Paused -> stringResource(
        if (f.paused == PauseReason.SourceDisabled) R.string.health_paused_disabled else R.string.health_paused_unauthorized,
    )
    ForwarderHealth.Backlog -> backlogDetail(f)
    ForwarderHealth.Healthy -> stringResource(R.string.health_healthy_detail, f.enrollment?.serverHost.orEmpty())
}

/**
 * Says what will happen to the waiting items: pending ones are retried
 * automatically, a held SMS backlog waits for the user, rejected ones are
 * never retried.
 */
@Composable
private fun backlogDetail(f: ForwarderState): String {
    val parts = buildList {
        if (f.queue.pending > 0) add(stringResource(R.string.health_backlog_pending, f.queue.pending))
        f.smsBacklog?.let { add(stringResource(R.string.health_backlog_held, it.count)) }
        if (f.queue.failed > 0) add(stringResource(R.string.health_backlog_failed, f.queue.failed))
    }
    return parts.joinToString("")
}
