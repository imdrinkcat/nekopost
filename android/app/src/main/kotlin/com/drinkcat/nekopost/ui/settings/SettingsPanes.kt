package com.drinkcat.nekopost.ui.settings

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.drinkcat.nekopost.R
import com.drinkcat.nekopost.core.forwarder.PauseReason
import com.drinkcat.nekopost.core.ui.component.EmptyState
import com.drinkcat.nekopost.core.ui.component.NekoIcons
import com.drinkcat.nekopost.core.ui.theme.StatusTone
import com.drinkcat.nekopost.core.ui.token.NekoTokens
import com.drinkcat.nekopost.lock.FoldCloseBehavior
import com.drinkcat.nekopost.data.UiPrefsState
import com.drinkcat.nekopost.ui.common.FootNote
import com.drinkcat.nekopost.ui.common.GroupDivider
import com.drinkcat.nekopost.ui.common.InfoRow
import com.drinkcat.nekopost.ui.common.NavRow
import com.drinkcat.nekopost.ui.common.NoticeCard
import com.drinkcat.nekopost.ui.common.SettingsGroup
import com.drinkcat.nekopost.ui.common.SettingsSection
import com.drinkcat.nekopost.ui.common.SwitchRow
import com.drinkcat.nekopost.ui.common.ValueRow
import com.drinkcat.nekopost.ui.formatDateTime
import com.drinkcat.nekopost.ui.shell.ShellActions
import com.drinkcat.nekopost.ui.shell.ShellUiState

// ---------------------------------------------------------------- 账户与服务器

/** Who this phone is: the forwarding source and the receiving device, each in its own group. */
@Composable
fun AccountPane(state: ShellUiState, actions: ShellActions) {
    val f = state.forwarder
    val e = f.enrollment
    SettingsSection(stringResource(R.string.account_forwarder))
    if (e == null) {
        SettingsGroup {
            NavRow(
                title = stringResource(R.string.account_enroll),
                summary = stringResource(R.string.health_not_enrolled_detail),
                icon = NekoIcons.QrScan,
                onClick = actions.onOpenSetup,
                modifier = Modifier.testTag("account_enroll"),
            )
        }
    } else {
        if (f.paused != null) {
            NoticeCard(
                text = stringResource(if (f.paused == PauseReason.SourceDisabled) R.string.health_paused_disabled else R.string.health_paused_unauthorized),
                tone = StatusTone.Error,
            )
        }
        SettingsGroup {
            InfoRow(stringResource(R.string.enrollment_server), e.serverHost, mono = true)
            InfoRow(stringResource(R.string.enrollment_source), e.sourceName)
            InfoRow(stringResource(R.string.enrollment_source_id), e.sourceId, mono = true)
            InfoRow(stringResource(R.string.enrollment_since), formatDateTime(e.enrolledAt))
        }
    }
    SettingsSection(stringResource(R.string.account_device))
    val d = state.device
    if (d == null) {
        SettingsGroup {
            NavRow(
                title = stringResource(R.string.pair_title),
                summary = stringResource(R.string.account_pair_detail),
                icon = Icons.Filled.Notifications,
                onClick = actions.settings.onPairThisPhone,
                modifier = Modifier.testTag("account_pair"),
            )
        }
    } else {
        SettingsGroup {
            InfoRow(stringResource(R.string.account_device_name), d.name.ifBlank { "—" })
            InfoRow(stringResource(R.string.enrollment_server), d.serverHost, mono = true)
            InfoRow(stringResource(R.string.account_device_role), roleText(d))
            InfoRow(stringResource(R.string.account_device_id), d.deviceId.take(12) + "…", mono = true)
        }
    }
    FootNote(stringResource(R.string.settings_server_note))
}

@Composable
private fun roleText(d: ThisDeviceUi): String = stringResource(
    when {
        d.canAdmin -> R.string.role_full
        d.canRead && d.canSend -> R.string.role_read_send
        d.canRead -> R.string.role_receiver
        else -> R.string.role_sender
    },
)

// ---------------------------------------------------------------- 消息通知

/** Push state, how notifications look, and where the server delivers (requirement 4, device side). */
@Composable
fun NotificationsPane(state: ShellUiState, actions: SettingsActions) {
    val d = state.device
    val n = state.notify
    if (d == null || !d.canRead) {
        EmptyState(
            title = stringResource(R.string.notify_settings_unpaired_title),
            message = stringResource(R.string.notify_settings_unpaired_detail),
        )
        Button(
            onClick = actions.onPairThisPhone,
            modifier = Modifier.fillMaxWidth().heightIn(min = NekoTokens.Touch.minTarget).testTag("notify_pair"),
        ) { Text(stringResource(R.string.pair_title)) }
        return
    }
    if (!n.permission) {
        NoticeCard(
            title = stringResource(R.string.notify_settings_blocked_title),
            text = stringResource(R.string.notify_settings_blocked_detail),
            tone = StatusTone.Warning,
            action = stringResource(R.string.notify_settings_open_system),
            onAction = actions.onOpenNotificationSettings,
            actionTag = "notify_enable",
        )
    }
    SettingsSection(stringResource(R.string.notify_settings_push))
    SettingsGroup {
        InfoRow(stringResource(R.string.notify_settings_push_state), pushStateText(d))
        if (d.canAdmin) {
            GroupDivider()
            NavRow(
                title = stringResource(if (n.testing) R.string.notify_settings_testing else R.string.notify_settings_test),
                summary = n.testResult ?: stringResource(R.string.notify_settings_test_detail),
                icon = Icons.AutoMirrored.Filled.Send,
                onClick = actions.onTestPush,
                modifier = Modifier.testTag("notify_test"),
            )
        }
    }
    SettingsSection(stringResource(R.string.notify_settings_style))
    SettingsGroup {
        SwitchRow(
            title = stringResource(R.string.notify_settings_content),
            summary = stringResource(if (n.showContent) R.string.notify_settings_content_on else R.string.notify_settings_content_off),
            checked = n.showContent,
            onCheckedChange = actions.onNotificationContent,
            modifier = Modifier.testTag("notify_content"),
        )
        GroupDivider(inset = false)
        InfoRow(stringResource(R.string.notify_settings_lockscreen), stringResource(R.string.notify_settings_lockscreen_value))
        GroupDivider(inset = false)
        NavRow(
            title = stringResource(R.string.notify_settings_channels),
            summary = stringResource(R.string.notify_settings_channels_detail),
            onClick = actions.onOpenNotificationSettings,
        )
    }
    FootNote(stringResource(R.string.notify_settings_lockscreen_note))
    if (d.serverKnown) {
        SettingsSection(stringResource(R.string.notify_settings_delivery))
        SettingsGroup {
            InfoRow(stringResource(R.string.notify_settings_telegram), telegramText(d.telegramTypes))
            InfoRow(
                stringResource(R.string.notify_settings_app_push),
                stringResource(if (d.pushEnabled) R.string.notify_settings_app_push_on else R.string.notify_settings_app_push_off),
            )
        }
        FootNote(stringResource(R.string.notify_settings_delivery_note))
    }
}

@Composable
private fun pushStateText(d: ThisDeviceUi): String = when (d.pushState) {
    null -> stringResource(R.string.notify_push_unknown)
    "ok", "active", "registered" -> stringResource(R.string.notify_push_ok)
    "", "none", "unregistered" -> stringResource(R.string.notify_push_none)
    "invalid", "stale" -> stringResource(R.string.notify_push_invalid)
    "locked_down", "paused" -> stringResource(R.string.notify_push_paused)
    else -> d.pushState
}

@Composable
private fun telegramText(types: List<String>?): String = when {
    types == null -> stringResource(R.string.notify_telegram_all)
    types.isEmpty() -> stringResource(R.string.notify_telegram_none)
    else -> stringResource(R.string.notify_telegram_some, types.joinToString("、") { typeName(it) })
}

@Composable
private fun typeName(type: String): String = when (type) {
    "sms" -> stringResource(R.string.type_sms)
    "notification" -> stringResource(R.string.type_notification)
    "note" -> stringResource(R.string.type_note)
    "script" -> stringResource(R.string.type_script)
    else -> type
}

// ---------------------------------------------------------------- 应用锁

/**
 * 应用锁 (requirement: 进入应用时开启密码): on by default once something is
 * paired; the "password" is the phone's own screen lock or fingerprint.
 * Without a secure lock screen the switch is off and explains itself.
 */
@Composable
fun AppLockPane(lock: LockSettingsUi, actions: SettingsActions) {
    if (!lock.secure) {
        NoticeCard(
            title = stringResource(R.string.lock_settings_insecure_title),
            text = stringResource(R.string.lock_settings_insecure_detail),
            tone = StatusTone.Warning,
            action = stringResource(R.string.lock_settings_open_security),
            onAction = actions.onOpenSecuritySettings,
            actionTag = "lock_open_security",
        )
    }
    val on = lock.enabled && lock.secure
    SettingsGroup {
        SwitchRow(
            title = stringResource(R.string.lock_settings_switch),
            summary = stringResource(R.string.lock_settings_switch_detail),
            checked = on,
            enabled = lock.secure,
            onCheckedChange = actions.onLockEnabled,
            modifier = Modifier.testTag("lock_switch"),
        )
    }
    SettingsSection(stringResource(R.string.lock_settings_when))
    SettingsGroup {
        ValueRow(
            title = stringResource(R.string.lock_settings_grace),
            value = lock.graceSeconds,
            choices = UiPrefsState.GraceChoices,
            label = { graceLabel(it) },
            onChange = actions.onLockGrace,
            enabled = on,
            modifier = Modifier.testTag("lock_grace"),
        )
        GroupDivider(inset = false)
        ValueRow(
            title = stringResource(R.string.lock_settings_idle),
            value = lock.idleMinutes,
            choices = UiPrefsState.IdleChoices,
            label = { idleLabel(it) },
            onChange = actions.onIdleLock,
            enabled = on,
            modifier = Modifier.testTag("lock_idle"),
        )
        if (lock.foldable) {
            GroupDivider(inset = false)
            ValueRow(
                title = stringResource(R.string.lock_settings_fold),
                value = lock.foldClose,
                choices = FoldCloseBehavior.entries,
                label = { foldLabel(it) },
                onChange = actions.onFoldClose,
                enabled = on,
                modifier = Modifier.testTag("lock_fold"),
            )
        }
        GroupDivider(inset = false)
        InfoRow(stringResource(R.string.lock_settings_screen_off), stringResource(R.string.lock_settings_screen_off_value))
    }
    FootNote(stringResource(R.string.lock_settings_scope))
    if (lock.foldable) {
        SettingsSection(stringResource(R.string.settings_section_fold))
        SettingsGroup {
            SwitchRow(
                title = stringResource(R.string.envelope_setting),
                summary = stringResource(R.string.envelope_setting_detail),
                checked = lock.envelope,
                onCheckedChange = actions.onEnvelope,
            )
        }
    }
}

@Composable
private fun graceLabel(seconds: Int): String = when (seconds) {
    0 -> stringResource(R.string.lock_now)
    in 1..59 -> stringResource(R.string.duration_seconds, seconds)
    else -> stringResource(R.string.duration_minutes, seconds / 60)
}

@Composable
private fun idleLabel(minutes: Int): String =
    if (minutes == 0) stringResource(R.string.lock_never) else stringResource(R.string.duration_minutes, minutes)

@Composable
private fun foldLabel(b: FoldCloseBehavior): String = stringResource(
    when (b) {
        FoldCloseBehavior.Curtain -> R.string.lock_fold_curtain
        FoldCloseBehavior.Lock -> R.string.lock_fold_lock
        FoldCloseBehavior.Off -> R.string.lock_fold_off
    },
)
