package com.drinkcat.nekopost.ui.setup

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.password
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.layout.padding
import com.drinkcat.nekopost.R
import com.drinkcat.nekopost.core.enroll.EnrollmentError
import com.drinkcat.nekopost.core.enroll.EnrollmentLink
import com.drinkcat.nekopost.core.forwarder.EnrollFailure
import com.drinkcat.nekopost.core.forwarder.OtpVerdict
import com.drinkcat.nekopost.core.ui.component.FadeInOnce
import com.drinkcat.nekopost.core.ui.component.KeyValueRow
import com.drinkcat.nekopost.core.ui.component.NekoCard
import com.drinkcat.nekopost.core.ui.component.NekoIcons
import com.drinkcat.nekopost.core.ui.component.PostmarkStamp
import com.drinkcat.nekopost.core.ui.component.StampLanding
import com.drinkcat.nekopost.core.ui.theme.NekopostTheme
import com.drinkcat.nekopost.core.ui.theme.StatusTone
import com.drinkcat.nekopost.core.ui.token.NekoTokens
import com.drinkcat.nekopost.ui.LocalDisplayZone
import com.drinkcat.nekopost.ui.LocalNow
import com.drinkcat.nekopost.ui.common.Bullet
import com.drinkcat.nekopost.ui.common.CheckLine
import com.drinkcat.nekopost.ui.common.GuidanceCard
import com.drinkcat.nekopost.ui.common.MonoBlock
import com.drinkcat.nekopost.ui.common.PathSteps
import com.drinkcat.nekopost.ui.formatDuration
import java.time.format.DateTimeFormatter

private val ButtonHeight = NekoTokens.Touch.minTarget

/** The explanation and actions of one step. */
@Composable
internal fun StepDetails(
    step: SetupStep,
    snapshot: SetupSnapshot,
    enroll: EnrollUiState,
    actions: SetupActions,
    showStamp: Boolean = true,
) {
    Column(verticalArrangement = Arrangement.spacedBy(NekoTokens.Spacing.l)) {
        if (step != SetupStep.Finish) {
            Text(
                stringResource(stepInfo(step).summary),
                style = MaterialTheme.typography.bodyLarge,
            )
        }
        when (step) {
            SetupStep.Welcome -> WelcomeStep()
            SetupStep.Enroll -> EnrollStep(snapshot, enroll, actions)
            SetupStep.RestrictedSettings -> RestrictedStep(snapshot, actions)
            SetupStep.SmsPermission -> SmsStep(snapshot, actions)
            SetupStep.NotificationAccess -> ListenerStep(snapshot, actions)
            SetupStep.PostNotifications -> PostNotificationsStep(snapshot, actions)
            SetupStep.Battery -> BatteryStep(snapshot, actions)
            SetupStep.CoverScreen -> CoverStep(snapshot, actions)
            SetupStep.OtpSelfTest -> OtpStep(snapshot, actions)
            SetupStep.Finish -> FinishStep(snapshot, actions, showStamp)
        }
    }
}

@Composable
private fun WelcomeStep() {
    NekoCard(title = stringResource(R.string.setup_install_title)) {
        stringArrayResource(R.array.setup_install_notes).forEach { Bullet(it) }
    }
    Text(
        stringResource(R.string.setup_welcome_privacy),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

// ---------------------------------------------------------------- enroll

@Composable
private fun EnrollStep(snapshot: SetupSnapshot, enroll: EnrollUiState, actions: SetupActions) {
    val enrollment = snapshot.enrollment
    if (enrollment != null) {
        NekoCard(containerColor = NekopostTheme.colors.success.container) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = NekopostTheme.colors.success.content)
                Spacer(Modifier.width(NekoTokens.Spacing.s))
                Text(
                    stringResource(R.string.setup_enroll_done, enrollment.sourceName),
                    style = MaterialTheme.typography.titleMedium,
                    color = NekopostTheme.colors.success.content,
                )
            }
            KeyValueRow(stringResource(R.string.enrollment_server), enrollment.serverHost, mono = true)
            KeyValueRow(stringResource(R.string.enrollment_source_id), enrollment.sourceId, mono = true)
        }
        return
    }

    IdentityChoice(actions.onPairDevice)

    when (enroll) {
        is EnrollUiState.Confirm -> ConfirmLinkCard(enroll.link, busy = false, failure = null, actions = actions)
        is EnrollUiState.Enrolling -> ConfirmLinkCard(enroll.link, busy = true, failure = null, actions = actions)
        is EnrollUiState.Failed -> ConfirmLinkCard(enroll.link, busy = false, failure = enroll, actions = actions)
        else -> ScanOrPaste(enroll, actions)
    }
}

/**
 * 转发 (this step: scan the source QR) or 接收消息 (pair this phone as a
 * receiving device: leaves the wizard for the pairing screen).
 */
@Composable
private fun IdentityChoice(onPairDevice: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(NekoTokens.Spacing.s)) {
        IdentityOption(
            title = stringResource(R.string.setup_identity_forward),
            detail = stringResource(R.string.setup_identity_forward_detail),
            selected = true,
            enabled = true,
        )
        IdentityOption(
            title = stringResource(R.string.setup_identity_device),
            detail = stringResource(R.string.setup_identity_device_detail),
            selected = false,
            enabled = true,
            onClick = onPairDevice,
            modifier = Modifier.testTag("setup_pair_device"),
        )
    }
}

@Composable
private fun IdentityOption(
    title: String,
    detail: String,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerLow,
        contentColor = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
        modifier = modifier
            .fillMaxWidth()
            .selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onClick),
    ) {
        Row(Modifier.padding(NekoTokens.Spacing.m), verticalAlignment = Alignment.CenterVertically) {
            RadioButton(selected = selected, onClick = null, enabled = enabled)
            Spacer(Modifier.width(NekoTokens.Spacing.s))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium, color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
                Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun ScanOrPaste(enroll: EnrollUiState, actions: SetupActions) {
    // Never saved to instance state: the text contains the source token.
    var text by remember { mutableStateOf("") }
    Column(verticalArrangement = Arrangement.spacedBy(NekoTokens.Spacing.m)) {
        Button(
            onClick = actions.onScanQr,
            modifier = Modifier.fillMaxWidth().heightIn(min = NekoTokens.Touch.coverPrimaryTarget).testTag("enroll_scan"),
        ) {
            Icon(NekoIcons.QrScan, contentDescription = null)
            Spacer(Modifier.width(NekoTokens.Spacing.s))
            Text(stringResource(R.string.setup_enroll_scan), style = MaterialTheme.typography.titleMedium)
        }
        Text(
            stringResource(R.string.setup_enroll_scan_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            label = { Text(stringResource(R.string.setup_enroll_paste_label)) },
            placeholder = { Text("nekopost://enroll?…") },
            singleLine = true,
            visualTransformation = TokenMaskTransformation,
            // Password type: keyboards neither learn nor suggest the token, and
            // accessibility services get it as a password field.
            keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { if (text.isNotBlank()) actions.onSubmitLink(text) }),
            isError = enroll is EnrollUiState.Invalid,
            modifier = Modifier.fillMaxWidth().semantics { password() }.testTag("enroll_paste_field"),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(NekoTokens.Spacing.s)) {
            FilledTonalButton(onClick = actions.onPasteFromClipboard, modifier = Modifier.heightIn(min = ButtonHeight)) {
                Text(stringResource(R.string.setup_enroll_paste_clipboard))
            }
            TextButton(
                onClick = { actions.onSubmitLink(text) },
                enabled = text.isNotBlank(),
                modifier = Modifier.heightIn(min = ButtonHeight).testTag("enroll_check"),
            ) {
                Text(stringResource(R.string.setup_enroll_check))
            }
        }
        when (enroll) {
            is EnrollUiState.Invalid -> ErrorNote(enrollmentErrorText(enroll.error))
            EnrollUiState.ScannerUnavailable -> ErrorNote(stringResource(R.string.setup_enroll_scanner_unavailable))
            else -> Unit
        }
    }
}

@Composable
private fun ConfirmLinkCard(link: EnrollmentLink, busy: Boolean, failure: EnrollUiState.Failed?, actions: SetupActions) {
    NekoCard(title = stringResource(R.string.setup_enroll_confirm_title), containerColor = MaterialTheme.colorScheme.surfaceContainer) {
        KeyValueRow(stringResource(R.string.enrollment_server), link.serverHost, mono = true)
        KeyValueRow(stringResource(R.string.enrollment_source), link.displayName)
        KeyValueRow(stringResource(R.string.enrollment_source_id), link.sourceId, mono = true)
        KeyValueRow(stringResource(R.string.enrollment_token), maskToken(link.token), mono = true)
        Text(
            stringResource(R.string.setup_enroll_confirm_warning),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (failure != null) ErrorNote(enrollFailureText(failure.failure, failure.httpStatus))
        Row(horizontalArrangement = Arrangement.spacedBy(NekoTokens.Spacing.s), verticalAlignment = Alignment.CenterVertically) {
            Button(
                onClick = actions.onConfirmEnroll,
                enabled = !busy,
                modifier = Modifier.heightIn(min = ButtonHeight).testTag("enroll_confirm"),
            ) {
                if (busy) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(NekoTokens.Spacing.s))
                    Text(stringResource(R.string.setup_enroll_verifying))
                } else {
                    Text(stringResource(if (failure != null) R.string.setup_enroll_retry else R.string.setup_enroll_confirm))
                }
            }
            TextButton(onClick = actions.onCancelEnroll, enabled = !busy, modifier = Modifier.heightIn(min = ButtonHeight)) {
                Text(stringResource(R.string.action_cancel))
            }
        }
    }
}

@Composable
private fun ErrorNote(text: String) {
    Surface(
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(NekoTokens.Spacing.m))
    }
}

/** "mgs_AbCd…wxyz": enough to recognise, not enough to use. */
internal fun maskToken(token: String): String =
    if (token.length <= 12) "mgs_••••" else token.take(8) + "••••" + token.takeLast(4)

/** Masks the value of `token=` in a pasted link without changing its length (identity offsets). */
internal object TokenMaskTransformation : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        val s = text.text
        val start = s.indexOf("token=", ignoreCase = true)
        if (start < 0) return TransformedText(text, OffsetMapping.Identity)
        val valueStart = start + "token=".length
        val end = s.indexOf('&', valueStart).let { if (it < 0) s.length else it }
        // Keep the "mgs_" prefix visible so the user can see it is a token.
        val keep = (valueStart + 4).coerceAtMost(end)
        val masked = s.substring(0, keep) + "•".repeat(end - keep) + s.substring(end)
        return TransformedText(AnnotatedString(masked), OffsetMapping.Identity)
    }
}

@Composable
internal fun enrollmentErrorText(error: EnrollmentError): String = stringResource(
    when (error) {
        EnrollmentError.TooLong, EnrollmentError.NotNekopostLink -> R.string.enroll_error_not_link
        EnrollmentError.UnsupportedVersion -> R.string.enroll_error_version
        EnrollmentError.UnsupportedKind -> R.string.enroll_error_kind
        EnrollmentError.MalformedQuery, EnrollmentError.DuplicateParameter,
        EnrollmentError.UnknownParameter, EnrollmentError.MissingParameter -> R.string.enroll_error_malformed
        EnrollmentError.InvalidServerUrl -> R.string.enroll_error_url
        EnrollmentError.InsecureServerUrl -> R.string.enroll_error_insecure
        EnrollmentError.InvalidSourceId -> R.string.enroll_error_source_id
        EnrollmentError.InvalidToken -> R.string.enroll_error_token
        EnrollmentError.InvalidName -> R.string.enroll_error_name
    },
)

@Composable
internal fun enrollFailureText(failure: EnrollFailure, httpStatus: Int?): String {
    val base = stringResource(
        when (failure) {
            EnrollFailure.Network -> R.string.enroll_failure_network
            EnrollFailure.CertificatePinning -> R.string.enroll_failure_pinning
            EnrollFailure.Unauthorized -> R.string.enroll_failure_unauthorized
            EnrollFailure.SourceDisabled -> R.string.enroll_failure_disabled
            EnrollFailure.Server -> R.string.enroll_failure_server
            EnrollFailure.AlreadyEnrolled -> R.string.enroll_failure_already
            EnrollFailure.SecureStorage -> R.string.enroll_failure_secure_storage
        },
    )
    return if (httpStatus != null) stringResource(R.string.enroll_failure_with_status, base, httpStatus) else base
}

// ---------------------------------------------------------------- permissions

@Composable
private fun RestrictedStep(snapshot: SetupSnapshot, actions: SetupActions) {
    if (snapshot.smsBlocked) ErrorNote(stringResource(R.string.setup_restricted_blocked))
    GuidanceCard(title = stringResource(R.string.setup_guide_oneui)) {
        PathSteps(stringArrayResource(R.array.path_restricted).toList())
        Text(stringResource(R.string.setup_restricted_missing_hint), style = MaterialTheme.typography.bodySmall)
    }
    ActionRow(
        primary = stringResource(R.string.setup_open_app_info) to actions.onOpenAppDetails,
        secondary = if (snapshot.statusOf(SetupStep.RestrictedSettings) != StepStatus.Done) {
            stringResource(R.string.setup_i_did_it) to { actions.onAcknowledge(SetupStep.RestrictedSettings) }
        } else {
            null
        },
    )
}

@Composable
private fun SmsStep(snapshot: SetupSnapshot, actions: SetupActions) {
    val p = snapshot.permissions
    NekoCard {
        CheckLine(stringResource(R.string.perm_receive_sms), p.receiveSms)
        CheckLine(stringResource(R.string.perm_read_sms), p.readSms)
    }
    if (snapshot.smsBlocked && !(p.receiveSms && p.readSms)) {
        ErrorNote(stringResource(R.string.setup_sms_blocked))
        ActionRow(
            primary = stringResource(R.string.setup_open_app_info) to actions.onOpenAppDetails,
            secondary = stringResource(R.string.setup_sms_request) to actions.onRequestSms,
        )
    } else if (!(p.receiveSms && p.readSms)) {
        ActionRow(primary = stringResource(R.string.setup_sms_request) to actions.onRequestSms)
    }
}

@Composable
private fun ListenerStep(snapshot: SetupSnapshot, actions: SetupActions) {
    NekoCard {
        CheckLine(stringResource(R.string.perm_notification_listener), snapshot.permissions.notificationListener)
    }
    GuidanceCard(title = stringResource(R.string.setup_guide_oneui)) {
        PathSteps(stringArrayResource(R.array.path_listener).toList())
    }
    if (!snapshot.permissions.notificationListener) {
        ActionRow(primary = stringResource(R.string.setup_listener_open) to actions.onOpenNotificationAccess)
    }
}

@Composable
private fun PostNotificationsStep(snapshot: SetupSnapshot, actions: SetupActions) {
    NekoCard {
        CheckLine(stringResource(R.string.perm_post_notifications), snapshot.permissions.postNotifications)
    }
    if (!snapshot.permissions.postNotifications) {
        ActionRow(primary = stringResource(R.string.setup_post_request) to actions.onRequestPostNotifications)
    }
}

@Composable
private fun BatteryStep(snapshot: SetupSnapshot, actions: SetupActions) {
    NekoCard {
        CheckLine(stringResource(R.string.perm_battery), snapshot.permissions.ignoringBatteryOptimizations)
    }
    if (!snapshot.permissions.ignoringBatteryOptimizations) {
        ActionRow(primary = stringResource(R.string.setup_battery_request) to actions.onRequestBatteryExemption)
    }
    GuidanceCard(title = stringResource(R.string.setup_battery_oneui_title)) {
        PathSteps(stringArrayResource(R.array.path_never_sleeping).toList())
        Text(stringResource(R.string.setup_battery_oneui_pause), style = MaterialTheme.typography.bodySmall)
        TextButton(onClick = actions.onOpenBatterySettings, modifier = Modifier.heightIn(min = ButtonHeight)) {
            Text(stringResource(R.string.setup_battery_open_settings))
        }
    }
}

@Composable
private fun CoverStep(snapshot: SetupSnapshot, actions: SetupActions) {
    GuidanceCard(title = stringResource(R.string.setup_guide_oneui)) {
        PathSteps(stringArrayResource(R.array.path_cover_screen).toList())
    }
    Text(
        stringResource(R.string.setup_cover_flex),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    ActionRow(
        primary = stringResource(R.string.setup_cover_open) to actions.onOpenDisplaySettings,
        secondary = if (snapshot.statusOf(SetupStep.CoverScreen) != StepStatus.Done) {
            stringResource(R.string.setup_i_did_it) to { actions.onAcknowledge(SetupStep.CoverScreen) }
        } else {
            null
        },
    )
}

@Composable
private fun OtpStep(snapshot: SetupSnapshot, actions: SetupActions) {
    val otp = snapshot.otp
    Column(verticalArrangement = Arrangement.spacedBy(NekoTokens.Spacing.s)) {
        Text(stringResource(R.string.setup_otp_send_from_other), style = MaterialTheme.typography.bodyMedium)
        MonoBlock(stringResource(R.string.setup_otp_sample))
    }
    val (tone, text) = when (otp.verdict) {
        OtpVerdict.NoData -> StatusTone.Neutral to stringResource(R.string.setup_otp_waiting)
        OtpVerdict.NoDelay -> StatusTone.Success to stringResource(
            R.string.setup_otp_ok,
            otp.maxDelay?.let { formatDuration(it) } ?: "—",
        )
        OtpVerdict.DelayDetected -> StatusTone.Warning to stringResource(R.string.otp_delayed_detail)
    }
    val colors = NekopostTheme.colors.tone(tone)
    Surface(shape = MaterialTheme.shapes.medium, color = colors.container, contentColor = colors.content, modifier = Modifier.fillMaxWidth()) {
        Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(NekoTokens.Spacing.l))
    }
    if (otp.verdict == OtpVerdict.NoData && snapshot.statusOf(SetupStep.OtpSelfTest) != StepStatus.Done) {
        TextButton(onClick = { actions.onAcknowledge(SetupStep.OtpSelfTest) }, modifier = Modifier.heightIn(min = ButtonHeight)) {
            Text(stringResource(R.string.setup_otp_later))
        }
    }
}

// ---------------------------------------------------------------- finish

@Composable
internal fun FinishStamp(snapshot: SetupSnapshot) {
    val now = LocalNow.current()
    val zone = LocalDisplayZone.current
    val date = DateTimeFormatter.ofPattern("yyyy.MM.dd").format(now.atZone(zone))
    val ready = snapshot.pendingRequired().isEmpty()
    StampLanding {
        PostmarkStamp(
            title = stringResource(R.string.brand_name),
            date = date,
            caption = stringResource(if (ready) R.string.setup_stamp_ready else R.string.setup_stamp_partial),
            ink = if (ready) MaterialTheme.colorScheme.primary else NekopostTheme.colors.warning.content,
        )
    }
}

@Composable
private fun FinishStep(snapshot: SetupSnapshot, actions: SetupActions, showStamp: Boolean) {
    val pending = snapshot.pendingRequired()
    Column(
        Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(NekoTokens.Spacing.m),
    ) {
        if (showStamp) FinishStamp(snapshot)
        FadeInOnce(delayMillis = 350) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(NekoTokens.Spacing.s)) {
                Text(
                    stringResource(if (pending.isEmpty()) R.string.setup_finish_ready else R.string.setup_finish_partial),
                    style = MaterialTheme.typography.headlineSmall,
                    textAlign = TextAlign.Center,
                )
                Text(
                    if (pending.isEmpty()) {
                        stringResource(R.string.setup_finish_ready_detail)
                    } else {
                        stringResource(R.string.setup_finish_partial_detail, pending.size)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                snapshot.appLock?.let { on ->
                    Text(
                        stringResource(if (on) R.string.setup_finish_lock_on else R.string.setup_finish_lock_insecure),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.testTag("setup_finish_lock"),
                    )
                }
            }
        }
    }
    if (pending.isEmpty()) {
        // A recap of what was checked, so the stamp has something to vouch for.
        val done = snapshot.visibleSteps().filter {
            it != SetupStep.Welcome && it != SetupStep.Finish && snapshot.statusOf(it) == StepStatus.Done
        }
        if (done.isNotEmpty()) {
            FadeInOnce(delayMillis = 500) {
                NekoCard(title = stringResource(R.string.setup_finish_recap_title)) {
                    done.forEach { s ->
                        CheckLine(stringResource(stepInfo(s).title), ok = true, okText = stringResource(R.string.step_done))
                    }
                }
            }
        }
    } else {
        NekoCard {
            pending.forEach { s ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                        .selectable(selected = false, role = Role.Button, onClick = { actions.onStepChange(s) }),
                ) {
                    StepBadge(0, StepStatus.NeedsAction)
                    Spacer(Modifier.width(NekoTokens.Spacing.m))
                    Text(stringResource(stepInfo(s).title), style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                    Text(stringResource(R.string.setup_go_fix), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                }
            }
        }
    }
}

// ---------------------------------------------------------------- shared

@Composable
private fun ActionRow(primary: Pair<String, () -> Unit>, secondary: Pair<String, () -> Unit>? = null) {
    Row(horizontalArrangement = Arrangement.spacedBy(NekoTokens.Spacing.s), verticalAlignment = Alignment.CenterVertically) {
        Button(onClick = primary.second, modifier = Modifier.heightIn(min = ButtonHeight).testTag("step_primary")) {
            Text(primary.first)
        }
        if (secondary != null) {
            TextButton(onClick = secondary.second, modifier = Modifier.heightIn(min = ButtonHeight).testTag("step_secondary")) {
                Text(secondary.first)
            }
        }
    }
}
