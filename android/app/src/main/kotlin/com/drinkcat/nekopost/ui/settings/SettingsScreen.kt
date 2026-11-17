package com.drinkcat.nekopost.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.selection.selectable
import com.drinkcat.nekopost.R
import com.drinkcat.nekopost.core.ui.component.SleepingCat
import com.drinkcat.nekopost.core.ui.fold.FoldState
import com.drinkcat.nekopost.core.ui.fold.HingeAwareSplit
import com.drinkcat.nekopost.core.ui.fold.SplitAxis
import com.drinkcat.nekopost.core.ui.theme.NekoTextStyles
import com.drinkcat.nekopost.core.ui.token.NekoTokens
import com.drinkcat.nekopost.lock.LocalAppLock
import com.drinkcat.nekopost.ui.common.AdaptiveListDetail
import com.drinkcat.nekopost.ui.common.DangerRow
import com.drinkcat.nekopost.ui.common.GroupDivider
import com.drinkcat.nekopost.ui.common.NavRow
import com.drinkcat.nekopost.ui.common.SettingsGroup
import com.drinkcat.nekopost.ui.common.LocalSpacedSettingsColumn
import com.drinkcat.nekopost.ui.common.SettingsSection
import com.drinkcat.nekopost.ui.forward.HealthPill
import com.drinkcat.nekopost.ui.forward.HealthRing
import com.drinkcat.nekopost.ui.shell.PaneMode
import com.drinkcat.nekopost.ui.shell.ShellActions
import com.drinkcat.nekopost.ui.shell.ShellMemory
import com.drinkcat.nekopost.ui.shell.ShellUiState

/** Settings entries shown for this state (identity card excluded). */
fun visibleSettings(state: ShellUiState): List<SettingsItem> = SettingsItem.entries.filter { item ->
    when (item) {
        SettingsItem.Account -> false
        SettingsItem.Apps, SettingsItem.SmsRules -> state.forwarder.isEnrolled
        SettingsItem.Admin -> state.device?.canAdmin == true
        SettingsItem.Unenroll -> state.forwarder.isEnrolled || state.device != null
        else -> true
    }
}

/**
 * 设置: an identity card, then grouped rows (消息与通知 · 转发 · 安全 · 维护, and
 * 解除配对 alone at the bottom).
 *  - Single (cover): list; an entry opens full screen, back returns.
 *  - TwoPane (inner display): list and detail side by side.
 *  - TopBottom (tabletop): the identity card (or the pairing QR) above the
 *    hinge, list and detail below where the fingers are.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(state: ShellUiState, fold: FoldState, mode: PaneMode, actions: ShellActions, memory: ShellMemory) {
    val items = visibleSettings(state)
    val chosen = memory.settingsItem?.takeIf { it == SettingsItem.Account || (it in items && !it.isAction) }
    var unenrollOpen by rememberSaveable(LocalAppLock.current.epoch) { mutableStateOf(false) }
    val onSelect: (SettingsItem) -> Unit = { item ->
        when (item) {
            SettingsItem.Setup -> actions.onOpenSetup()
            SettingsItem.Unenroll -> unenrollOpen = true
            else -> {
                if (item != memory.settingsItem) memory.adminPage = null
                memory.settingsItem = item
            }
        }
    }
    val onBack = { memory.settingsItem = null }

    when (mode) {
        PaneMode.Single, PaneMode.TwoPane -> {
            val twoPane = mode == PaneMode.TwoPane
            val shown = chosen ?: SettingsItem.Account
            AdaptiveListDetail(
                fold = fold,
                twoPane = twoPane,
                showDetail = chosen != null,
                onBack = onBack,
                list = { SettingsList(items, if (twoPane) shown else null, state, header = true, onSelect) },
                detail = { SettingsDetail(shown, state, fold, actions, memory, onBack = if (twoPane) null else onBack) },
                modifier = Modifier.testTag(if (twoPane) "settings_two_pane" else "settings_single"),
            )
        }

        PaneMode.TopBottom -> HingeAwareSplit(
            fold = fold,
            axis = SplitAxis.Vertical,
            modifier = Modifier.fillMaxSize().testTag("settings_tabletop"),
            first = {
                val pairing = state.admin?.pairing
                if (chosen == SettingsItem.Admin && memory.adminPage == AdminPage.AddDevice && pairing != null) {
                    PairingDeskDisplay(pairing)
                } else {
                    IdentityDisplay(state)
                }
            },
            second = {
                AdaptiveListDetail(
                    fold = fold,
                    twoPane = false,
                    showDetail = chosen != null,
                    onBack = onBack,
                    list = { SettingsList(items, null, state, header = true, onSelect, title = false) },
                    detail = { SettingsDetail(chosen ?: SettingsItem.Account, state, fold, actions, memory, onBack = onBack, tabletop = true) },
                )
            },
        )
    }

    if (unenrollOpen) {
        ModalBottomSheet(
            onDismissRequest = { unenrollOpen = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        ) {
            UnenrollSheetContent(
                enrollment = state.forwarder.enrollment,
                device = state.device,
                pending = state.forwarder.queue.pending,
                onConfirm = { target ->
                    unenrollOpen = false
                    if (target != UnpairTarget.Forwarder) actions.settings.onUnpairDevice()
                    if (target != UnpairTarget.Device) actions.onUnenroll()
                },
                onCancel = { unenrollOpen = false },
            )
        }
    }
}

@Composable
private fun SettingsList(
    items: List<SettingsItem>,
    selected: SettingsItem?,
    state: ShellUiState,
    header: Boolean,
    onSelect: (SettingsItem) -> Unit,
    title: Boolean = true,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = NekoTokens.Spacing.l, end = NekoTokens.Spacing.l, bottom = NekoTokens.Spacing.xl),
    ) {
        if (title) item(key = "title") {
            Text(
                stringResource(R.string.settings_title),
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.padding(vertical = NekoTokens.Spacing.l).semantics { heading() },
            )
        }
        if (header) {
            item(key = "identity") {
                if (title) {
                    IdentityCard(state, selected = selected == SettingsItem.Account, onClick = { onSelect(SettingsItem.Account) })
                } else {
                    // Tabletop: the identity is already big on the upper half; here only the way in.
                    SettingsGroup(Modifier.padding(top = NekoTokens.Spacing.l)) {
                        NavRow(
                            title = stringResource(SettingsItem.Account.title),
                            summary = state.forwarder.enrollment?.serverHost ?: state.device?.serverHost,
                            icon = SettingsItem.Account.icon,
                            modifier = Modifier.testTag("settings_Account"),
                            onClick = { onSelect(SettingsItem.Account) },
                        )
                    }
                }
            }
        }
        val sections = items.groupBy { it.section }
        SettingsGroupKind.entries.forEach { section ->
            val rows = sections[section] ?: return@forEach
            item(key = "section_${section.name}") {
                Column {
                    if (section != SettingsGroupKind.Danger) {
                        SettingsSection(stringResource(section.label))
                    } else {
                        Spacer(Modifier.padding(top = NekoTokens.Spacing.xl))
                    }
                    SettingsGroup {
                        rows.forEachIndexed { i, item ->
                            if (i > 0) GroupDivider()
                            val tag = Modifier.testTag("settings_${item.name}")
                            if (item.danger) {
                                DangerRow(
                                    title = stringResource(item.title),
                                    summary = summary(item, state),
                                    icon = item.icon,
                                    modifier = tag,
                                    onClick = { onSelect(item) },
                                )
                            } else {
                                NavRow(
                                    title = stringResource(item.title),
                                    summary = summary(item, state),
                                    icon = item.icon,
                                    selected = item == selected,
                                    modifier = tag,
                                    onClick = { onSelect(item) },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun summary(item: SettingsItem, state: ShellUiState): String = when (item) {
    SettingsItem.Account -> ""
    SettingsItem.Notifications -> when {
        state.device == null -> stringResource(R.string.settings_notifications_unpaired)
        !state.notify.permission -> stringResource(R.string.settings_notifications_blocked)
        state.notify.showContent -> stringResource(R.string.settings_notifications_on)
        else -> stringResource(R.string.settings_notifications_hidden)
    }
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
    SettingsItem.AppLock -> when {
        !state.lock.secure -> stringResource(R.string.lock_settings_summary_insecure)
        !state.lock.enabled -> stringResource(R.string.lock_settings_summary_off)
        else -> stringResource(R.string.lock_settings_summary_on)
    }
    SettingsItem.Admin -> stringResource(R.string.settings_admin_summary)
    SettingsItem.Setup -> if (state.setupPending > 0) {
        stringResource(R.string.setup_banner, state.setupPending)
    } else {
        stringResource(R.string.settings_setup_done)
    }
    SettingsItem.Unenroll -> stringResource(R.string.settings_unenroll_summary)
    SettingsItem.About -> stringResource(R.string.settings_about_detail, state.appVersion)
}

/** Who this phone is: the forwarding source name and the server. Opens 账户与服务器. */
@Composable
private fun IdentityCard(state: ShellUiState, selected: Boolean, onClick: () -> Unit) {
    val e = state.forwarder.enrollment
    val title = e?.sourceName ?: state.device?.name ?: stringResource(R.string.settings_server_none)
    val host = e?.serverHost ?: state.device?.serverHost
    Surface(
        shape = MaterialTheme.shapes.large,
        color = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = selected, role = Role.Button, onClick = onClick)
            .testTag("settings_Account"),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.heightIn(min = 72.dp).padding(NekoTokens.Spacing.l),
        ) {
            SleepingCat(size = 44.dp)
            Spacer(Modifier.width(NekoTokens.Spacing.l))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(NekoTokens.Spacing.xxs)) {
                Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    host ?: stringResource(R.string.settings_account_none_detail),
                    style = if (host != null) NekoTextStyles.mono else MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                // On its own line: the host is never cut off by the pill in a narrow list pane.
                if (e != null) {
                    Spacer(Modifier.height(NekoTokens.Spacing.xs))
                    HealthPill(state.forwarder)
                }
            }
        }
    }
}

/** Tabletop upper half: the identity, larger, with the health ring (read only). */
@Composable
private fun IdentityDisplay(state: ShellUiState) {
    val e = state.forwarder.enrollment
    Row(
        modifier = Modifier.fillMaxSize().padding(NekoTokens.Spacing.xl),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(NekoTokens.Spacing.xl),
    ) {
        if (e != null) HealthRing(state.forwarder, state.otp, size = 120.dp, thickness = 10.dp) else SleepingCat(size = 120.dp)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(NekoTokens.Spacing.s)) {
            Text(stringResource(R.string.settings_title), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            Text(
                e?.sourceName ?: state.device?.name ?: stringResource(R.string.settings_server_none),
                style = MaterialTheme.typography.headlineMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            (e?.serverHost ?: state.device?.serverHost)?.let {
                Text(it, style = NekoTextStyles.mono, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (e != null) HealthPill(state.forwarder)
        }
    }
}

@Composable
private fun SettingsDetail(
    item: SettingsItem,
    state: ShellUiState,
    fold: FoldState,
    actions: ShellActions,
    memory: ShellMemory,
    onBack: (() -> Unit)?,
    tabletop: Boolean = false,
) {
    Column(Modifier.fillMaxSize().testTag("settings_detail_${item.name}")) {
        // 设备与管理 has pages with their own back arrow.
        if (item == SettingsItem.Admin) {
            Box(Modifier.weight(1f)) { AdminPane(state.admin ?: AdminUi(), state.device, memory, actions.settings.admin, onBack, tabletop) }
            return@Column
        }
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
                    .padding(bottom = NekoTokens.Spacing.xl),
                verticalArrangement = Arrangement.spacedBy(NekoTokens.Spacing.m),
            ) {
                CompositionLocalProvider(LocalSpacedSettingsColumn provides true) {
                when (item) {
                    SettingsItem.Account -> AccountPane(state, actions)
                    SettingsItem.Notifications -> NotificationsPane(state, actions.settings)
                    SettingsItem.SmsRules -> SmsRulesPane(state.smsRules, actions.onSmsRulesChange)
                    SettingsItem.AppLock -> AppLockPane(state.lock, actions.settings)
                    SettingsItem.About -> AboutPane(state, memory, actions.onCopy)
                    else -> Unit
                }
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
            .heightIn(min = 64.dp)
            .padding(start = if (onBack != null) NekoTokens.Spacing.xs else NekoTokens.Spacing.l, end = NekoTokens.Spacing.l),
    ) {
        if (onBack != null) {
            IconButton(onClick = onBack, modifier = Modifier.testTag("settings_back")) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
            }
            Spacer(Modifier.width(NekoTokens.Spacing.xs))
        }
        Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
    }
}
