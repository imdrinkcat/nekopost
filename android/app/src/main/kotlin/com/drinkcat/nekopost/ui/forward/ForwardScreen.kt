package com.drinkcat.nekopost.ui.forward

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.drinkcat.nekopost.R
import com.drinkcat.nekopost.core.forwarder.Enrollment
import com.drinkcat.nekopost.core.forwarder.ForwarderHealth
import com.drinkcat.nekopost.core.forwarder.ForwarderState
import com.drinkcat.nekopost.core.forwarder.OtpHealth
import com.drinkcat.nekopost.core.forwarder.OtpVerdict
import com.drinkcat.nekopost.core.forwarder.PauseReason
import com.drinkcat.nekopost.core.forwarder.PermissionStatus
import com.drinkcat.nekopost.core.forwarder.health
import com.drinkcat.nekopost.core.ui.component.KeyValueRow
import com.drinkcat.nekopost.core.ui.component.NekoCard
import com.drinkcat.nekopost.core.ui.component.SleepingCat
import com.drinkcat.nekopost.core.ui.component.StatusPill
import com.drinkcat.nekopost.core.ui.fold.FoldState
import com.drinkcat.nekopost.core.ui.fold.HingeAwareSplit
import com.drinkcat.nekopost.core.ui.fold.SplitAxis
import com.drinkcat.nekopost.core.ui.theme.NekoTextStyles
import com.drinkcat.nekopost.core.ui.theme.NekopostTheme
import com.drinkcat.nekopost.core.ui.theme.StatusTone
import com.drinkcat.nekopost.core.ui.token.NekoTokens
import com.drinkcat.nekopost.ui.failureReasonText
import com.drinkcat.nekopost.ui.formatDateTime
import com.drinkcat.nekopost.ui.formatDuration
import com.drinkcat.nekopost.ui.shell.PaneMode
import com.drinkcat.nekopost.ui.shell.ShellActions
import com.drinkcat.nekopost.ui.shell.ShellUiState

/**
 * 转发 (home): forwarding health at a glance.
 *  - Single: one column of cards.
 *  - TwoPane: status and controls on the left, diagnostics on the right.
 *  - TopBottom (tabletop): a read-only status display above the hinge, controls below.
 */
@Composable
fun ForwardScreen(state: ShellUiState, fold: FoldState, mode: PaneMode, actions: ShellActions) {
    val f = state.forwarder
    val pad = NekoTokens.Spacing.l
    when (mode) {
        PaneMode.Single -> LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(pad),
            verticalArrangement = Arrangement.spacedBy(NekoTokens.Spacing.m),
        ) {
            item { HealthHeader(f) }
            if (f.enrollment == null) {
                item { EnrollCard(actions) }
            } else {
                item { SwitchesCard(f, actions) }
                item { QueueCard(f, actions) }
            }
            item { OtpCard(state.otp) }
            item { PermissionsCard(f.permissions) }
        }

        PaneMode.TwoPane -> HingeAwareSplit(
            fold = fold,
            axis = SplitAxis.Horizontal,
            fraction = 0.46f,
            gap = NekoTokens.Spacing.m,
            modifier = Modifier.fillMaxSize(),
            first = {
                ScrollColumn {
                    HealthHeader(f)
                    if (f.enrollment == null) {
                        EnrollCard(actions)
                    } else {
                        SwitchesCard(f, actions)
                        QueueCard(f, actions)
                    }
                }
            },
            second = {
                ScrollColumn {
                    OtpCard(state.otp)
                    PermissionsCard(f.permissions)
                    f.enrollment?.let { EnrollmentCard(it) }
                }
            },
        )

        PaneMode.TopBottom -> HingeAwareSplit(
            fold = fold,
            axis = SplitAxis.Vertical,
            modifier = Modifier.fillMaxSize(),
            first = { TabletopDisplay(f, state.otp) },
            second = {
                ScrollColumn {
                    if (f.enrollment == null) {
                        EnrollCard(actions)
                    } else {
                        SwitchesCard(f, actions)
                        TabletopButtons(actions)
                    }
                }
            },
        )
    }
}

@Composable
private fun ScrollColumn(content: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(NekoTokens.Spacing.l),
        verticalArrangement = Arrangement.spacedBy(NekoTokens.Spacing.m),
    ) {
        content()
    }
}

private data class HealthText(val title: Int, val tone: StatusTone)

private fun healthText(h: ForwarderHealth) = when (h) {
    ForwarderHealth.NotEnrolled -> HealthText(R.string.health_not_enrolled, StatusTone.Neutral)
    ForwarderHealth.Idle -> HealthText(R.string.health_idle, StatusTone.Neutral)
    ForwarderHealth.NeedsPermission -> HealthText(R.string.health_needs_permission, StatusTone.Warning)
    ForwarderHealth.Paused -> HealthText(R.string.health_paused, StatusTone.Error)
    ForwarderHealth.Backlog -> HealthText(R.string.health_backlog, StatusTone.Warning)
    ForwarderHealth.Healthy -> HealthText(R.string.health_healthy, StatusTone.Success)
}

@Composable
private fun healthDetail(f: ForwarderState): String = when (f.health()) {
    ForwarderHealth.NotEnrolled -> stringResource(R.string.health_not_enrolled_detail)
    ForwarderHealth.Idle -> stringResource(R.string.health_idle_detail)
    ForwarderHealth.NeedsPermission -> stringResource(R.string.health_needs_permission_detail)
    ForwarderHealth.Paused -> stringResource(
        if (f.paused == PauseReason.SourceDisabled) R.string.health_paused_disabled else R.string.health_paused_unauthorized,
    )
    ForwarderHealth.Backlog -> stringResource(R.string.health_backlog_detail)
    ForwarderHealth.Healthy -> stringResource(R.string.health_healthy_detail, f.enrollment?.serverHost.orEmpty())
}

/** Brand header: name, health pill and one sentence of explanation. */
@Composable
private fun HealthHeader(f: ForwarderState) {
    val h = healthText(f.health())
    NekoCard(containerColor = MaterialTheme.colorScheme.surfaceContainer) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SleepingCat(size = 56.dp)
            Spacer(Modifier.width(NekoTokens.Spacing.m))
            Column(verticalArrangement = Arrangement.spacedBy(NekoTokens.Spacing.xs)) {
                Text(stringResource(R.string.brand_name), style = MaterialTheme.typography.headlineSmall)
                StatusPill(text = stringResource(h.title), tone = h.tone)
            }
        }
        Text(
            text = healthDetail(f),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun EnrollCard(actions: ShellActions) {
    NekoCard(title = stringResource(R.string.enroll_title)) {
        Text(stringResource(R.string.enroll_body), style = MaterialTheme.typography.bodyMedium)
        Button(
            onClick = actions.onScanEnrollment,
            modifier = Modifier.fillMaxWidth().heightIn(min = NekoTokens.Touch.minTarget),
        ) {
            Text(stringResource(R.string.enroll_scan))
        }
    }
}

@Composable
internal fun EnrollmentCard(e: Enrollment) {
    NekoCard(title = stringResource(R.string.enrollment_title)) {
        KeyValueRow(stringResource(R.string.enrollment_server), e.serverHost, mono = true)
        KeyValueRow(stringResource(R.string.enrollment_source), e.sourceName)
        KeyValueRow(stringResource(R.string.enrollment_source_id), e.sourceId, mono = true)
        KeyValueRow(stringResource(R.string.enrollment_since), formatDateTime(e.enrolledAt))
    }
}

@Composable
private fun SwitchesCard(f: ForwarderState, actions: ShellActions) {
    NekoCard(title = stringResource(R.string.switches_title)) {
        SwitchRow(
            title = stringResource(R.string.switch_sms),
            detail = stringResource(R.string.switch_sms_detail),
            checked = f.smsEnabled,
            onCheckedChange = actions.onSmsEnabledChange,
        )
        SwitchRow(
            title = stringResource(R.string.switch_notifications),
            detail = stringResource(R.string.switch_notifications_detail, f.notificationApps.size),
            checked = f.notificationsEnabled,
            onCheckedChange = actions.onNotificationsEnabledChange,
        )
    }
}

@Composable
private fun SwitchRow(title: String, detail: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = NekoTokens.Touch.minTarget),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(NekoTokens.Spacing.m))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun QueueCard(f: ForwarderState, actions: ShellActions) {
    NekoCard(title = stringResource(R.string.queue_title)) {
        Row(horizontalArrangement = Arrangement.spacedBy(NekoTokens.Spacing.xl)) {
            Stat(label = stringResource(R.string.queue_pending), value = f.queue.pending)
            Stat(label = stringResource(R.string.queue_failed), value = f.queue.failed)
        }
        KeyValueRow(
            stringResource(R.string.queue_last_success),
            f.lastSuccess?.let { formatDateTime(it.at) } ?: stringResource(R.string.queue_none),
        )
        KeyValueRow(
            stringResource(R.string.queue_last_failure),
            f.lastFailure?.let {
                stringResource(R.string.failure_with_reason, formatDateTime(it.at), failureReasonText(it.reason))
            } ?: stringResource(R.string.queue_none),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(NekoTokens.Spacing.s)) {
            FilledTonalButton(onClick = actions.onRetryNow, modifier = Modifier.heightIn(min = NekoTokens.Touch.minTarget)) {
                Text(stringResource(R.string.queue_retry))
            }
            OutlinedButton(onClick = actions.onSendTest, modifier = Modifier.heightIn(min = NekoTokens.Touch.minTarget)) {
                Text(stringResource(R.string.queue_send_test))
            }
        }
    }
}

@Composable
private fun Stat(label: String, value: Int, large: Boolean = false) {
    Column {
        Text(
            text = value.toString(),
            style = if (large) NekoTextStyles.statNumber else MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun otpTone(v: OtpVerdict) = when (v) {
    OtpVerdict.NoData -> StatusTone.Neutral
    OtpVerdict.NoDelay -> StatusTone.Success
    OtpVerdict.DelayDetected -> StatusTone.Warning
}

@Composable
private fun otpVerdictText(v: OtpVerdict) = stringResource(
    when (v) {
        OtpVerdict.NoData -> R.string.otp_no_data
        OtpVerdict.NoDelay -> R.string.otp_no_delay
        OtpVerdict.DelayDetected -> R.string.otp_delayed
    },
)

@Composable
private fun OtpCard(otp: OtpHealth) {
    NekoCard(title = stringResource(R.string.otp_title)) {
        StatusPill(text = otpVerdictText(otp.verdict), tone = otpTone(otp.verdict))
        Text(
            text = when (otp.verdict) {
                OtpVerdict.NoData -> stringResource(R.string.otp_no_data_detail)
                OtpVerdict.NoDelay -> stringResource(R.string.otp_no_delay_detail, otp.windowDays)
                OtpVerdict.DelayDetected -> stringResource(R.string.otp_delayed_detail)
            },
            style = MaterialTheme.typography.bodyMedium,
        )
        if (otp.verdict != OtpVerdict.NoData) {
            Text(
                text = stringResource(R.string.otp_stats, otp.realtimeCount, otp.reconciledCount),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            otp.maxDelay?.let {
                Text(
                    text = stringResource(R.string.otp_max_delay, formatDuration(it)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun PermissionsCard(p: PermissionStatus) {
    NekoCard(title = stringResource(R.string.permissions_title)) {
        PermissionRow(stringResource(R.string.perm_receive_sms), p.receiveSms)
        PermissionRow(stringResource(R.string.perm_read_sms), p.readSms)
        PermissionRow(stringResource(R.string.perm_notification_listener), p.notificationListener)
        PermissionRow(stringResource(R.string.perm_post_notifications), p.postNotifications)
        PermissionRow(stringResource(R.string.perm_battery), p.ignoringBatteryOptimizations)
    }
}

@Composable
private fun PermissionRow(label: String, granted: Boolean) {
    val tone = NekopostTheme.colors.tone(if (granted) StatusTone.Success else StatusTone.Warning)
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Icon(
            imageVector = if (granted) Icons.Filled.CheckCircle else Icons.Filled.Warning,
            contentDescription = null,
            tint = tone.content,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(NekoTokens.Spacing.m))
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Text(
            text = stringResource(if (granted) R.string.perm_granted else R.string.perm_missing),
            style = MaterialTheme.typography.labelMedium,
            color = tone.content,
        )
    }
}

/** Upper half in tabletop: large, glanceable, nothing to tap. */
@Composable
private fun TabletopDisplay(f: ForwarderState, otp: OtpHealth) {
    val h = healthText(f.health())
    Box(modifier = Modifier.fillMaxSize().padding(NekoTokens.Spacing.xl), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(NekoTokens.Spacing.l),
        ) {
            StatusPill(text = stringResource(h.title), tone = h.tone, large = true)
            Row(horizontalArrangement = Arrangement.spacedBy(NekoTokens.Spacing.xxl)) {
                Stat(label = stringResource(R.string.queue_pending), value = f.queue.pending, large = true)
                Stat(label = stringResource(R.string.queue_failed), value = f.queue.failed, large = true)
            }
            Text(
                text = stringResource(R.string.queue_last_success) + "  " +
                    (f.lastSuccess?.let { formatDateTime(it.at) } ?: stringResource(R.string.queue_none)),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            StatusPill(text = otpVerdictText(otp.verdict), tone = otpTone(otp.verdict))
        }
    }
}

/** Lower half in tabletop: big targets only. */
@Composable
private fun TabletopButtons(actions: ShellActions) {
    Row(horizontalArrangement = Arrangement.spacedBy(NekoTokens.Spacing.m), modifier = Modifier.fillMaxWidth()) {
        FilledTonalButton(
            onClick = actions.onRetryNow,
            modifier = Modifier.weight(1f).heightIn(min = NekoTokens.Touch.tabletopTarget),
        ) {
            Text(stringResource(R.string.queue_retry))
        }
        Button(
            onClick = actions.onSendTest,
            modifier = Modifier.weight(1f).heightIn(min = NekoTokens.Touch.tabletopTarget),
        ) {
            Text(stringResource(R.string.queue_send_test))
        }
    }
}
