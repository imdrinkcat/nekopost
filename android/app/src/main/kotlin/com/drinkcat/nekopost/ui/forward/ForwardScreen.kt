package com.drinkcat.nekopost.ui.forward

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.drinkcat.nekopost.R
import com.drinkcat.nekopost.core.forwarder.Enrollment
import com.drinkcat.nekopost.core.forwarder.ForwarderState
import com.drinkcat.nekopost.core.forwarder.OtpHealth
import com.drinkcat.nekopost.core.forwarder.OtpVerdict
import com.drinkcat.nekopost.core.forwarder.SendTestResult
import com.drinkcat.nekopost.core.forwarder.health
import com.drinkcat.nekopost.core.ui.component.KeyValueRow
import com.drinkcat.nekopost.core.ui.component.NekoCard
import com.drinkcat.nekopost.core.ui.component.NekoIcons
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
import com.drinkcat.nekopost.ui.formatRelative
import com.drinkcat.nekopost.ui.kindText
import com.drinkcat.nekopost.ui.shell.PaneMode
import com.drinkcat.nekopost.ui.shell.ShellActions
import com.drinkcat.nekopost.ui.shell.ShellMemory
import com.drinkcat.nekopost.ui.shell.ShellUiState
import com.drinkcat.nekopost.ui.shell.TestSendState

/**
 * 转发 (home): forwarding health at a glance.
 *  - Single (cover): one column, large targets.
 *  - TwoPane (inner display): status and controls on the left, diagnostics on the right.
 *  - TopBottom (tabletop): the live monitor above the hinge, controls below.
 */
@Composable
fun ForwardScreen(
    state: ShellUiState,
    fold: FoldState,
    mode: PaneMode,
    actions: ShellActions,
    memory: ShellMemory,
) {
    val f = state.forwarder
    // SMS held by the reconciler (more than SmsBacklog.Threshold) until the user decides.
    val backlog = f.smsBacklog?.takeIf { f.isEnrolled }
    val showBacklog = backlog != null && backlog.count > memory.backlogDismissedAt
    // A resolved backlog resets "稍后", so the next one is shown whatever its size.
    LaunchedEffect(backlog == null, f.isEnrolled) {
        if (backlog == null && f.isEnrolled) memory.backlogDismissedAt = 0
    }
    val banners: @Composable () -> Unit = {
        if (showBacklog && backlog != null) {
            BacklogBanner(
                pending = backlog.count,
                onSubmitAll = { actions.onResolveSmsBacklog(true) },
                onSkipAll = { actions.onResolveSmsBacklog(false) },
                onLater = { memory.backlogDismissedAt = backlog.count },
            )
        }
        if (state.setupPending > 0) SetupBanner(state.setupPending, actions.onOpenSetup)
    }

    when (mode) {
        PaneMode.Single -> LazyColumn(
            modifier = Modifier.fillMaxSize().testTag("forward_single"),
            contentPadding = PaddingValues(NekoTokens.Spacing.l),
            verticalArrangement = Arrangement.spacedBy(NekoTokens.Spacing.m),
        ) {
            item { HeroCard(state, compact = true) }
            if (showBacklog || state.setupPending > 0) {
                item { Column(verticalArrangement = Arrangement.spacedBy(NekoTokens.Spacing.m)) { banners() } }
            }
            if (f.enrollment == null) {
                item { EnrollCard(actions) }
            } else {
                item { StatusCard(f, actions) }
                item { TestCard(state.testSend, actions, large = false) }
            }
            item { OtpCard(state.otp) }
        }

        PaneMode.TwoPane -> HingeAwareSplit(
            fold = fold,
            axis = SplitAxis.Horizontal,
            fraction = 0.5f,
            gap = NekoTokens.Spacing.s,
            modifier = Modifier.fillMaxSize().testTag("forward_two_pane"),
            first = {
                ScrollColumn {
                    HeroCard(state, compact = false)
                    banners()
                    if (f.enrollment == null) EnrollCard(actions) else StatusCard(f, actions)
                }
            },
            second = {
                ScrollColumn {
                    if (f.enrollment != null) TestCard(state.testSend, actions, large = false)
                    OtpCard(state.otp)
                    f.enrollment?.let { EnrollmentCard(it) }
                }
            },
        )

        PaneMode.TopBottom -> HingeAwareSplit(
            fold = fold,
            axis = SplitAxis.Vertical,
            modifier = Modifier.fillMaxSize().testTag("forward_tabletop"),
            first = { ForwardingMonitor(state) },
            second = {
                ScrollColumn {
                    banners()
                    if (f.enrollment == null) {
                        EnrollCard(actions)
                    } else {
                        TabletopControls(state, actions)
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

/** Brand header: health ring, name, health pill and one sentence. */
@Composable
private fun HeroCard(state: ShellUiState, compact: Boolean) {
    val f = state.forwarder
    val h = healthText(f.health())
    NekoCard(containerColor = MaterialTheme.colorScheme.surfaceContainer) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (f.enrollment == null) {
                SleepingCat(size = if (compact) 72.dp else 88.dp)
            } else {
                HealthRing(f, state.otp, size = if (compact) 92.dp else 112.dp, thickness = if (compact) 9.dp else 10.dp)
            }
            Spacer(Modifier.width(NekoTokens.Spacing.l))
            Column(verticalArrangement = Arrangement.spacedBy(NekoTokens.Spacing.xs), modifier = Modifier.weight(1f)) {
                Text(
                    stringResource(R.string.brand_name),
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.semantics { heading() },
                )
                StatusPill(text = stringResource(h.title), tone = h.tone)
                f.enrollment?.let {
                    Text(
                        "${it.sourceName} · ${it.serverHost}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        Text(
            text = healthDetail(f),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (f.enrollment != null && !compact) HealthLegend(f, state.otp, columns = 3)
    }
}

@Composable
private fun BacklogBanner(pending: Int, onSubmitAll: () -> Unit, onSkipAll: () -> Unit, onLater: () -> Unit) {
    val tone = NekopostTheme.colors.warning
    Surface(shape = MaterialTheme.shapes.medium, color = tone.container, contentColor = tone.content, modifier = Modifier.fillMaxWidth().testTag("backlog_banner")) {
        Column(Modifier.padding(NekoTokens.Spacing.l), verticalArrangement = Arrangement.spacedBy(NekoTokens.Spacing.s)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(NekoTokens.Spacing.s))
                Text(stringResource(R.string.backlog_title, pending), style = MaterialTheme.typography.titleMedium)
            }
            Text(stringResource(R.string.backlog_body), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
            Row(horizontalArrangement = Arrangement.spacedBy(NekoTokens.Spacing.s)) {
                Button(onClick = onSubmitAll, modifier = Modifier.heightIn(min = NekoTokens.Touch.minTarget)) {
                    Text(stringResource(R.string.backlog_submit_all, pending))
                }
                TextButton(onClick = onSkipAll, modifier = Modifier.heightIn(min = NekoTokens.Touch.minTarget)) {
                    Text(stringResource(R.string.backlog_skip_all))
                }
                TextButton(onClick = onLater, modifier = Modifier.heightIn(min = NekoTokens.Touch.minTarget)) {
                    Text(stringResource(R.string.backlog_later))
                }
            }
        }
    }
}

@Composable
private fun SetupBanner(pending: Int, onOpenSetup: () -> Unit) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(start = NekoTokens.Spacing.l, end = NekoTokens.Spacing.s, top = NekoTokens.Spacing.s, bottom = NekoTokens.Spacing.s), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Warning, contentDescription = null, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(NekoTokens.Spacing.m))
            Text(stringResource(R.string.setup_banner, pending), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            TextButton(onClick = onOpenSetup, modifier = Modifier.heightIn(min = NekoTokens.Touch.minTarget)) {
                Text(stringResource(R.string.setup_banner_action))
            }
        }
    }
}

@Composable
private fun EnrollCard(actions: ShellActions) {
    NekoCard(title = stringResource(R.string.enroll_title)) {
        Text(stringResource(R.string.enroll_body), style = MaterialTheme.typography.bodyMedium)
        Button(
            onClick = actions.onOpenSetup,
            modifier = Modifier.fillMaxWidth().heightIn(min = NekoTokens.Touch.coverPrimaryTarget),
        ) {
            Icon(NekoIcons.QrScan, contentDescription = null)
            Spacer(Modifier.width(NekoTokens.Spacing.s))
            Text(stringResource(R.string.enroll_scan), style = MaterialTheme.typography.titleMedium)
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

/** The forwarding health card: what is on, what is waiting, what went wrong last. */
@Composable
private fun StatusCard(f: ForwarderState, actions: ShellActions) {
    NekoCard(title = stringResource(R.string.status_title)) {
        SwitchRow(
            tone = f.toneOf(HealthPart.Sms, OtpHealth.Empty),
            title = stringResource(R.string.switch_sms),
            detail = healthPartState(HealthPart.Sms, f, OtpHealth.Empty),
            checked = f.smsEnabled,
            onCheckedChange = actions.onSmsEnabledChange,
            tag = "switch_sms",
        )
        SwitchRow(
            tone = f.toneOf(HealthPart.Notifications, OtpHealth.Empty),
            title = stringResource(R.string.switch_notifications),
            detail = healthPartState(HealthPart.Notifications, f, OtpHealth.Empty),
            checked = f.notificationsEnabled,
            onCheckedChange = actions.onNotificationsEnabledChange,
            tag = "switch_notifications",
        )
        Row(horizontalArrangement = Arrangement.spacedBy(NekoTokens.Spacing.xl), modifier = Modifier.padding(vertical = NekoTokens.Spacing.xs)) {
            Stat(label = stringResource(R.string.queue_pending), value = f.queue.pending, tone = if (f.queue.pending > 0) StatusTone.Warning else StatusTone.Neutral)
            Stat(label = stringResource(R.string.queue_failed), value = f.queue.failed, tone = if (f.queue.failed > 0) StatusTone.Error else StatusTone.Neutral)
        }
        KeyValueRow(
            stringResource(R.string.queue_last_success),
            f.lastSuccess?.let { stringResource(R.string.event_with_kind, formatRelative(it.at), kindText(it.kind)) }
                ?: stringResource(R.string.queue_none),
        )
        KeyValueRow(
            stringResource(R.string.queue_last_failure),
            f.lastFailure?.let {
                val reason = failureReasonText(it.reason)
                val text = stringResource(R.string.failure_with_reason, formatRelative(it.at), reason)
                if (it.detail != null) "$text\n${it.detail}" else text
            } ?: stringResource(R.string.queue_none),
        )
        val canRetry = f.queue.pending > 0 || f.paused != null
        if (canRetry || f.queue.failed > 0) {
            Row(horizontalArrangement = Arrangement.spacedBy(NekoTokens.Spacing.s)) {
                if (canRetry) {
                    FilledTonalButton(onClick = actions.onRetryNow, modifier = Modifier.heightIn(min = NekoTokens.Touch.minTarget)) {
                        Text(stringResource(R.string.queue_retry))
                    }
                }
                if (f.queue.failed > 0) {
                    OutlinedButton(onClick = actions.onClearFailed, modifier = Modifier.heightIn(min = NekoTokens.Touch.minTarget)) {
                        Text(stringResource(R.string.queue_clear_failed))
                    }
                }
            }
        }
    }
}

@Composable
private fun SwitchRow(
    tone: StatusTone,
    title: String,
    detail: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    tag: String,
    minHeight: Dp = NekoTokens.Touch.minTarget,
) {
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = minHeight),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Dot(tone, size = 10.dp)
        Spacer(Modifier.width(NekoTokens.Spacing.m))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(NekoTokens.Spacing.m))
        Switch(checked = checked, onCheckedChange = onCheckedChange, modifier = Modifier.testTag(tag))
    }
}

@Composable
private fun Stat(label: String, value: Int, tone: StatusTone, large: Boolean = false) {
    Column {
        Text(
            text = value.toString(),
            style = if (large) NekoTextStyles.statNumber else MaterialTheme.typography.headlineMedium,
            color = if (tone == StatusTone.Neutral) MaterialTheme.colorScheme.onSurface else NekopostTheme.colors.tone(tone).content,
        )
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** "发送测试消息" with its result shown in place. */
@Composable
private fun TestCard(test: TestSendState, actions: ShellActions, large: Boolean) {
    NekoCard(title = stringResource(R.string.test_title)) {
        Text(stringResource(R.string.test_body), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        TestButton(test, actions, large)
        TestResult(test)
    }
}

@Composable
private fun TestButton(test: TestSendState, actions: ShellActions, large: Boolean, modifier: Modifier = Modifier) {
    val sending = test is TestSendState.Sending
    Button(
        onClick = actions.onSendTest,
        enabled = !sending,
        modifier = modifier
            .heightIn(min = if (large) NekoTokens.Touch.tabletopTarget else NekoTokens.Touch.minTarget)
            .testTag("send_test"),
    ) {
        if (sending) {
            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
        } else {
            Icon(NekoIcons.TestLetter, contentDescription = null, modifier = Modifier.size(20.dp))
        }
        Spacer(Modifier.width(NekoTokens.Spacing.s))
        Text(stringResource(if (sending) R.string.test_sending else R.string.queue_send_test))
    }
}

@Composable
private fun TestResult(test: TestSendState) {
    AnimatedVisibility(visible = test is TestSendState.Done) {
        val result = (test as? TestSendState.Done)?.result ?: return@AnimatedVisibility
        val (tone, text) = when (result) {
            is SendTestResult.Accepted -> StatusTone.Success to if (result.duplicate) {
                stringResource(R.string.test_result_duplicate)
            } else {
                stringResource(R.string.test_result_accepted, result.serverMessageId ?: "—")
            }
            is SendTestResult.Queued -> StatusTone.Warning to stringResource(R.string.test_result_queued, failureReasonText(result.reason))
            is SendTestResult.Rejected -> StatusTone.Error to stringResource(R.string.test_result_rejected, failureReasonText(result.reason))
            SendTestResult.NotEnrolled -> StatusTone.Neutral to stringResource(R.string.test_result_not_enrolled)
        }
        val colors = NekopostTheme.colors.tone(tone)
        Surface(shape = MaterialTheme.shapes.small, color = colors.container, contentColor = colors.content, modifier = Modifier.fillMaxWidth().testTag("test_result")) {
            Row(Modifier.padding(NekoTokens.Spacing.m), verticalAlignment = Alignment.CenterVertically) {
                Icon(if (tone == StatusTone.Success) Icons.Filled.CheckCircle else Icons.Filled.Warning, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(NekoTokens.Spacing.s))
                Text(text, style = MaterialTheme.typography.bodyMedium)
            }
        }
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
    NekoCard(
        title = stringResource(R.string.otp_title),
        trailing = { StatusPill(text = otpVerdictText(otp.verdict), tone = otpTone(otp.verdict)) },
    ) {
        Text(
            text = when (otp.verdict) {
                OtpVerdict.NoData -> stringResource(R.string.otp_no_data_detail)
                OtpVerdict.NoDelay -> stringResource(R.string.otp_no_delay_detail, otp.windowDays)
                OtpVerdict.DelayDetected -> stringResource(R.string.otp_delayed_detail)
            },
            style = MaterialTheme.typography.bodyMedium,
        )
        if (otp.verdict != OtpVerdict.NoData) {
            Row(horizontalArrangement = Arrangement.spacedBy(NekoTokens.Spacing.xl)) {
                Stat(stringResource(R.string.otp_realtime), otp.realtimeCount, StatusTone.Neutral)
                Stat(
                    stringResource(R.string.otp_reconciled),
                    otp.reconciledCount,
                    if (otp.reconciledCount > 0) StatusTone.Warning else StatusTone.Neutral,
                )
            }
            otp.medianDelay?.let {
                KeyValueRow(stringResource(R.string.otp_median_delay), formatDuration(it))
            }
            otp.maxDelay?.let {
                KeyValueRow(stringResource(R.string.otp_max_delay_label), formatDuration(it))
            }
        }
        if (otp.verdict == OtpVerdict.DelayDetected) {
            Text(
                stringResource(R.string.otp_delayed_advice),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Lower half in tabletop: switches and big buttons only. */
@Composable
private fun TabletopControls(state: ShellUiState, actions: ShellActions) {
    val f = state.forwarder
    NekoCard {
        SwitchRow(
            tone = f.toneOf(HealthPart.Sms, OtpHealth.Empty),
            title = stringResource(R.string.switch_sms),
            detail = healthPartState(HealthPart.Sms, f, OtpHealth.Empty),
            checked = f.smsEnabled,
            onCheckedChange = actions.onSmsEnabledChange,
            tag = "switch_sms",
            minHeight = 56.dp,
        )
        SwitchRow(
            tone = f.toneOf(HealthPart.Notifications, OtpHealth.Empty),
            title = stringResource(R.string.switch_notifications),
            detail = healthPartState(HealthPart.Notifications, f, OtpHealth.Empty),
            checked = f.notificationsEnabled,
            onCheckedChange = actions.onNotificationsEnabledChange,
            tag = "switch_notifications",
            minHeight = 56.dp,
        )
    }
    Row(horizontalArrangement = Arrangement.spacedBy(NekoTokens.Spacing.m), modifier = Modifier.fillMaxWidth()) {
        FilledTonalButton(
            onClick = actions.onRetryNow,
            enabled = f.queue.pending > 0 || f.paused != null,
            modifier = Modifier.weight(1f).heightIn(min = NekoTokens.Touch.tabletopTarget),
        ) {
            Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(NekoTokens.Spacing.s))
            Text(stringResource(R.string.queue_retry))
        }
        TestButton(state.testSend, actions, large = true, modifier = Modifier.weight(1f))
    }
    TestResult(state.testSend)
}
