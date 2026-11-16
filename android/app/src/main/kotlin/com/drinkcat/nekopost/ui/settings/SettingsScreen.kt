package com.drinkcat.nekopost.ui.settings

import androidx.annotation.StringRes
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.drinkcat.nekopost.R
import com.drinkcat.nekopost.core.forwarder.PauseReason
import com.drinkcat.nekopost.core.ui.component.EmptyState
import com.drinkcat.nekopost.core.ui.component.NekoCard
import com.drinkcat.nekopost.core.ui.component.NekoIcons
import com.drinkcat.nekopost.core.ui.fold.FoldState
import com.drinkcat.nekopost.core.ui.fold.HingeAwareSplit
import com.drinkcat.nekopost.core.ui.fold.SplitAxis
import com.drinkcat.nekopost.core.ui.theme.NekopostTheme
import com.drinkcat.nekopost.core.ui.token.NekoTokens
import com.drinkcat.nekopost.ui.common.AdaptiveListDetail
import com.drinkcat.nekopost.ui.common.PaneHeader
import com.drinkcat.nekopost.ui.forward.EnrollmentCard
import com.drinkcat.nekopost.ui.forward.ForwardingMonitor
import com.drinkcat.nekopost.ui.shell.PaneMode
import com.drinkcat.nekopost.ui.shell.ShellActions
import com.drinkcat.nekopost.ui.shell.ShellMemory
import com.drinkcat.nekopost.ui.shell.ShellUiState

enum class SettingsItem(@param:StringRes val title: Int, val icon: ImageVector, val danger: Boolean = false) {
    Server(R.string.settings_server, NekoIcons.Server),
    Apps(R.string.settings_apps, Icons.Filled.Notifications),
    SmsRules(R.string.settings_sms_rules, NekoIcons.Sms),
    Setup(R.string.settings_setup, Icons.Filled.Build),
    Security(R.string.settings_security, Icons.Filled.Lock),
    Unenroll(R.string.settings_unenroll, Icons.Filled.Delete, danger = true),
    About(R.string.settings_about, Icons.Filled.Info),
}

/**
 * 设置: list + detail.
 *  - Single (cover): list; an entry opens full screen, back returns.
 *  - TwoPane (inner display): ListDetailPaneScaffold.
 *  - TopBottom (tabletop): the live monitor above the hinge, the settings
 *    (list, then detail) below where the fingers are.
 */
@Composable
fun SettingsScreen(state: ShellUiState, fold: FoldState, mode: PaneMode, actions: ShellActions, memory: ShellMemory) {
    val items = SettingsItem.entries.filter { it != SettingsItem.Unenroll || state.forwarder.isEnrolled }
    val chosen = memory.settingsItem?.takeIf { it in items }
    val onSelect: (SettingsItem) -> Unit = { memory.settingsItem = it }
    val onBack = { memory.settingsItem = null }

    when (mode) {
        PaneMode.Single, PaneMode.TwoPane -> {
            val twoPane = mode == PaneMode.TwoPane
            val shown = chosen ?: SettingsItem.Server
            AdaptiveListDetail(
                fold = fold,
                twoPane = twoPane,
                showDetail = chosen != null,
                onBack = onBack,
                list = { SettingsList(items, if (twoPane) shown else null, state, onSelect) },
                detail = { SettingsDetail(shown, state, actions, memory, onBack = if (twoPane) null else onBack) },
                modifier = Modifier.testTag(if (twoPane) "settings_two_pane" else "settings_single"),
            )
        }

        PaneMode.TopBottom -> HingeAwareSplit(
            fold = fold,
            axis = SplitAxis.Vertical,
            modifier = Modifier.fillMaxSize().testTag("settings_tabletop"),
            first = { ForwardingMonitor(state) },
            second = {
                AdaptiveListDetail(
                    fold = fold,
                    twoPane = false,
                    showDetail = chosen != null,
                    onBack = onBack,
                    list = { SettingsList(items, null, state, onSelect) },
                    detail = { SettingsDetail(chosen ?: SettingsItem.Server, state, actions, memory, onBack = onBack) },
                )
            },
        )
    }
}

@Composable
private fun SettingsList(
    items: List<SettingsItem>,
    selected: SettingsItem?,
    state: ShellUiState,
    onSelect: (SettingsItem) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(NekoTokens.Spacing.l),
        verticalArrangement = Arrangement.spacedBy(NekoTokens.Spacing.s),
    ) {
        item(key = "title") { PaneHeader(stringResource(R.string.settings_title)) }
        items(items, key = { it.name }) { item ->
            SettingsRow(item, summary = summary(item, state), selected = item == selected, onClick = { onSelect(item) })
        }
    }
}

@Composable
private fun summary(item: SettingsItem, state: ShellUiState): String = when (item) {
    SettingsItem.Server -> state.forwarder.enrollment?.let { "${it.sourceName} · ${it.serverHost}" }
        ?: stringResource(R.string.settings_server_none)
    SettingsItem.Apps -> if (state.forwarder.notificationsEnabled) {
        stringResource(R.string.switch_notifications_detail, state.forwarder.notificationApps.size)
    } else {
        stringResource(R.string.settings_apps_off)
    }
    SettingsItem.SmsRules -> if (state.smsRules.isEmpty()) {
        stringResource(R.string.settings_sms_rules_none)
    } else {
        stringResource(R.string.settings_sms_rules_count, state.smsRules.size)
    }
    SettingsItem.Setup -> if (state.setupPending > 0) {
        stringResource(R.string.setup_banner, state.setupPending)
    } else {
        stringResource(R.string.settings_setup_done)
    }
    SettingsItem.Security -> stringResource(R.string.settings_security_detail)
    SettingsItem.Unenroll -> stringResource(R.string.settings_unenroll_summary)
    SettingsItem.About -> stringResource(R.string.settings_about_detail, state.appVersion)
}

@Composable
private fun SettingsRow(item: SettingsItem, summary: String, selected: Boolean, onClick: () -> Unit) {
    val accent = if (item.danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
    Surface(
        shape = MaterialTheme.shapes.small,
        color = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = selected, role = Role.Button, onClick = onClick)
            .testTag("settings_${item.name}"),
    ) {
        Row(
            modifier = Modifier
                .heightIn(min = 68.dp)
                .padding(horizontal = NekoTokens.Spacing.l, vertical = NekoTokens.Spacing.m),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(
                shape = CircleShape,
                color = if (item.danger) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceContainerHighest,
                modifier = Modifier.size(40.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(item.icon, contentDescription = null, tint = accent, modifier = Modifier.size(20.dp))
                }
            }
            Spacer(Modifier.width(NekoTokens.Spacing.l))
            Column(Modifier.weight(1f)) {
                Text(
                    stringResource(item.title),
                    style = MaterialTheme.typography.titleMedium,
                    color = if (item.danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    summary,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = MaterialTheme.colorScheme.outline)
        }
    }
}

@Composable
private fun SettingsDetail(
    item: SettingsItem,
    state: ShellUiState,
    actions: ShellActions,
    memory: ShellMemory,
    onBack: (() -> Unit)?,
) {
    Column(Modifier.fillMaxSize().testTag("settings_detail_${item.name}")) {
        DetailHeader(stringResource(item.title), onBack)
        // The app picker scrolls itself (lazy list); everything else is a plain scrolling column.
        if (item == SettingsItem.Apps) {
            Box(Modifier.weight(1f)) { AppPickerPane(state, actions, memory) }
        } else {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = NekoTokens.Spacing.l)
                    .padding(bottom = NekoTokens.Spacing.l),
                verticalArrangement = Arrangement.spacedBy(NekoTokens.Spacing.m),
            ) {
                when (item) {
                    SettingsItem.Server -> ServerDetail(state, actions)
                    SettingsItem.SmsRules -> SmsRulesPane(state.smsRules, actions.onSmsRulesChange)
                    SettingsItem.Setup -> SetupDetail(state, actions)
                    SettingsItem.Security -> NekoCard {
                        Text(stringResource(R.string.settings_security_body), style = MaterialTheme.typography.bodyMedium)
                    }
                    SettingsItem.Unenroll -> UnenrollPane(state, actions)
                    SettingsItem.About -> AboutPane(state, memory, actions.onCopy)
                    SettingsItem.Apps -> Unit
                }
            }
        }
    }
}

@Composable
internal fun DetailHeader(title: String, onBack: (() -> Unit)?) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .padding(start = if (onBack != null) NekoTokens.Spacing.xs else NekoTokens.Spacing.l, end = NekoTokens.Spacing.l),
    ) {
        if (onBack != null) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
            }
            Spacer(Modifier.width(NekoTokens.Spacing.xs))
        }
        Text(title, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { heading() })
    }
}

@Composable
private fun ServerDetail(state: ShellUiState, actions: ShellActions) {
    val f = state.forwarder
    val e = f.enrollment
    if (e == null) {
        EmptyState(
            title = stringResource(R.string.settings_server_none),
            message = stringResource(R.string.health_not_enrolled_detail),
        )
        Button(onClick = actions.onOpenSetup, modifier = Modifier.fillMaxWidth().heightIn(min = NekoTokens.Touch.minTarget)) {
            Text(stringResource(R.string.enroll_scan))
        }
        return
    }
    if (f.paused != null) {
        val tone = NekopostTheme.colors.error
        Surface(shape = MaterialTheme.shapes.medium, color = tone.container, contentColor = tone.content, modifier = Modifier.fillMaxWidth()) {
            Text(
                stringResource(if (f.paused == PauseReason.SourceDisabled) R.string.health_paused_disabled else R.string.health_paused_unauthorized),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(NekoTokens.Spacing.l),
            )
        }
    }
    EnrollmentCard(e)
    Text(
        stringResource(R.string.settings_server_note),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun SetupDetail(state: ShellUiState, actions: ShellActions) {
    NekoCard {
        Text(stringResource(R.string.settings_setup_body), style = MaterialTheme.typography.bodyMedium)
        Button(
            onClick = actions.onOpenSetup,
            modifier = Modifier.heightIn(min = NekoTokens.Touch.minTarget).testTag("open_setup"),
        ) {
            Text(stringResource(R.string.settings_setup_open))
        }
    }
}
