package com.drinkcat.nekopost.inbox.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.drinkcat.nekopost.R
import com.drinkcat.nekopost.core.ui.component.EmptyState
import com.drinkcat.nekopost.core.ui.component.NekoIcons
import com.drinkcat.nekopost.core.ui.theme.StatusTone
import com.drinkcat.nekopost.core.ui.token.NekoTokens
import com.drinkcat.nekopost.ui.common.InfoRow
import com.drinkcat.nekopost.ui.common.NoticeCard
import com.drinkcat.nekopost.ui.common.SettingsGroup
import com.drinkcat.nekopost.ui.common.SubScreenTopBar

/** 消息 before this phone is paired as a receiving device: one clear way in. */
@Composable
fun InboxUnpaired(revoked: Boolean, onPair: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(NekoTokens.Spacing.l).testTag("inbox_unpaired"),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(NekoTokens.Spacing.m),
    ) {
        Text(
            stringResource(R.string.inbox_title),
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.fillMaxWidth().padding(vertical = NekoTokens.Spacing.s),
        )
        if (revoked) {
            NoticeCard(
                title = stringResource(R.string.inbox_revoked_title),
                text = stringResource(R.string.inbox_revoked_detail),
                tone = StatusTone.Error,
            )
        }
        EmptyState(
            title = stringResource(R.string.inbox_unpaired_title),
            message = stringResource(R.string.inbox_unpaired_detail),
        )
        Button(
            onClick = onPair,
            modifier = Modifier.widthIn(min = 240.dp).heightIn(min = NekoTokens.Touch.coverPrimaryTarget).testTag("inbox_pair"),
        ) {
            Icon(NekoIcons.QrScan, contentDescription = null)
            Spacer(Modifier.padding(start = NekoTokens.Spacing.s))
            Text(stringResource(R.string.pair_title), style = MaterialTheme.typography.titleMedium)
        }
        Text(
            stringResource(R.string.inbox_unpaired_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Paired, but only to forward (no msg:read): nothing to show here. */
@Composable
fun ReceiveDisabledPane(onOpenSettings: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier.fillMaxSize().padding(NekoTokens.Spacing.l),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(NekoTokens.Spacing.m),
    ) {
        EmptyState(
            title = stringResource(R.string.inbox_send_only_title),
            message = stringResource(R.string.inbox_send_only_detail),
        )
        FilledTonalButton(onClick = onOpenSettings, modifier = Modifier.heightIn(min = NekoTokens.Touch.minTarget)) {
            Text(stringResource(R.string.inbox_open_settings))
        }
    }
}

/** 键盘快捷键 (§10.5), listed only when a hardware keyboard is attached. */
@Composable
fun KeyboardShortcutsPane(onBack: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxSize().testTag("keyboard_shortcuts")) {
        SubScreenTopBar(stringResource(R.string.inbox_tool_shortcuts), onBack)
        Column(
            Modifier.verticalScroll(rememberScrollState()).padding(horizontal = NekoTokens.Spacing.l),
        ) {
            SettingsGroup {
                InfoRow("J / K", stringResource(R.string.shortcut_next_previous), mono = false)
                InfoRow("Enter", stringResource(R.string.shortcut_open))
                InfoRow("Esc", stringResource(R.string.shortcut_back))
                InfoRow("C", stringResource(R.string.shortcut_copy_code))
                InfoRow("E", stringResource(R.string.shortcut_toggle_read))
                InfoRow("Ctrl / ⌘ + F", stringResource(R.string.shortcut_search))
                InfoRow("Ctrl / ⌘ + K", stringResource(R.string.shortcut_menu))
            }
        }
    }
}
