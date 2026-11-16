@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.drinkcat.nekopost.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.drinkcat.nekopost.R
import com.drinkcat.nekopost.core.ui.component.EmptyState
import com.drinkcat.nekopost.core.ui.component.HoldToConfirmButton
import com.drinkcat.nekopost.core.ui.component.NekoIcons
import com.drinkcat.nekopost.core.ui.component.StatusPill
import com.drinkcat.nekopost.core.ui.theme.NekoTextStyles
import com.drinkcat.nekopost.core.ui.theme.StatusTone
import com.drinkcat.nekopost.core.ui.token.NekoTokens
import com.drinkcat.nekopost.lock.LocalAppLock
import com.drinkcat.nekopost.ui.common.FootNote
import com.drinkcat.nekopost.ui.common.GroupDivider
import com.drinkcat.nekopost.ui.common.InfoRow
import com.drinkcat.nekopost.ui.common.NavRow
import com.drinkcat.nekopost.ui.common.NoticeCard
import com.drinkcat.nekopost.ui.common.RowIcon
import com.drinkcat.nekopost.ui.common.SettingsGroup
import com.drinkcat.nekopost.ui.common.SettingsSection
import com.drinkcat.nekopost.ui.common.SubScreenTopBar
import com.drinkcat.nekopost.ui.shell.ShellMemory

/**
 * 设置 › 设备与管理 (admin devices only; replaces the inbox 管理 page). A list of
 * pages, with 添加设备 up front. Every operation that changes access goes
 * through k_reauth (strong biometric) in the controller; the destructive ones
 * (吊销, 删除, 轮换) ask first with their consequences and a 1.5 s hold.
 */
@Composable
fun AdminPane(
    ui: AdminUi,
    device: ThisDeviceUi?,
    memory: ShellMemory,
    actions: AdminActions,
    onBack: (() -> Unit)?,
    tabletop: Boolean = false,
) {
    val page = memory.adminPage
    LaunchedEffect(page) { page?.let(actions.onLoad) }
    val toList = { memory.adminPage = null }
    when (page) {
        null -> AdminHome(ui, onBack, onOpen = { memory.adminPage = it })
        AdminPage.Devices -> DevicesPage(ui, device, actions, onBack = toList, onAdd = { memory.adminPage = AdminPage.AddDevice })
        AdminPage.AddDevice -> AddDevicePage(ui, actions, tabletop, onBack = {
            actions.onClosePairing()
            memory.adminPage = AdminPage.Devices
        })
        AdminPage.Sources -> SourcesPage(ui, actions, onBack = toList)
        AdminPage.Keys -> KeysPage(ui, actions, onBack = toList)
        AdminPage.Audit -> AuditPage(ui, onBack = toList)
        AdminPage.Server -> ServerPage(ui, onBack = toList)
    }
}

@Composable
private fun Page(title: String, onBack: (() -> Unit)?, tag: String, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxSize().testTag(tag)) {
        SubScreenTopBar(title, onBack)
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = NekoTokens.Spacing.l)
                .padding(bottom = NekoTokens.Spacing.xl),
            verticalArrangement = Arrangement.spacedBy(NekoTokens.Spacing.m),
        ) { content() }
    }
}

@Composable
private fun Feedback(ui: AdminUi, actions: AdminActions) {
    ui.secret?.let { SecretCard(it, actions) }
    ui.feedback?.let { NoticeCard(text = it, tone = StatusTone.Neutral) }
}

@Composable
private fun AdminHome(ui: AdminUi, onBack: (() -> Unit)?, onOpen: (AdminPage) -> Unit) {
    Page(stringResource(R.string.settings_admin), onBack, "admin_home") {
        Button(
            onClick = { onOpen(AdminPage.AddDevice) },
            modifier = Modifier.fillMaxWidth().heightIn(min = NekoTokens.Touch.coverPrimaryTarget).testTag("admin_add_device"),
        ) {
            Icon(Icons.Filled.Add, contentDescription = null)
            Spacer(Modifier.width(NekoTokens.Spacing.s))
            Text(stringResource(R.string.admin_add_device), style = MaterialTheme.typography.titleMedium)
        }
        FootNote(stringResource(R.string.admin_add_device_hint))
        SettingsGroup {
            NavRow(
                stringResource(R.string.admin_devices),
                summary = ui.devices?.let { list -> stringResource(R.string.admin_devices_count, list.count { !it.revoked }) },
                icon = NekoIcons.CoverScreen,
                onClick = { onOpen(AdminPage.Devices) },
                modifier = Modifier.testTag("admin_Devices"),
            )
            GroupDivider()
            NavRow(stringResource(R.string.admin_sources), stringResource(R.string.admin_sources_detail), NekoIcons.PostBox, onClick = { onOpen(AdminPage.Sources) })
            GroupDivider()
            NavRow(stringResource(R.string.admin_keys), stringResource(R.string.admin_keys_detail), Icons.Filled.Lock, onClick = { onOpen(AdminPage.Keys) })
            GroupDivider()
            NavRow(stringResource(R.string.admin_audit), stringResource(R.string.admin_audit_detail), Icons.Filled.Person, onClick = { onOpen(AdminPage.Audit) })
            GroupDivider()
            NavRow(stringResource(R.string.admin_server), stringResource(R.string.admin_server_detail), Icons.Filled.Info, onClick = { onOpen(AdminPage.Server) })
        }
    }
}

// ---------------------------------------------------------------- 设备

@Composable
private fun DevicesPage(ui: AdminUi, device: ThisDeviceUi?, actions: AdminActions, onBack: () -> Unit, onAdd: () -> Unit) {
    var revoke by rememberSaveable(LocalAppLock.current.epoch) { mutableStateOf<String?>(null) }
    Page(stringResource(R.string.admin_devices), onBack, "admin_devices") {
        Button(onClick = onAdd, modifier = Modifier.fillMaxWidth().heightIn(min = NekoTokens.Touch.minTarget)) {
            Icon(Icons.Filled.Add, contentDescription = null)
            Spacer(Modifier.width(NekoTokens.Spacing.s))
            Text(stringResource(R.string.admin_add_device))
        }
        Feedback(ui, actions)
        val list = ui.devices
        when {
            list == null -> Loading()
            list.isEmpty() -> EmptyState(stringResource(R.string.admin_devices_empty), stringResource(R.string.admin_add_device_hint), showCat = false)
            else -> SettingsGroup {
                list.forEachIndexed { i, d ->
                    if (i > 0) GroupDivider()
                    DeviceRow(d, self = d.self || d.id == device?.deviceId, busy = ui.busy, onTest = { actions.onTestPush(d.id) }, onRevoke = { revoke = d.id })
                }
            }
        }
    }
    val target = revoke?.let { id -> ui.devices?.firstOrNull { it.id == id } }
    if (target != null) {
        DangerDialog(
            title = stringResource(R.string.admin_revoke_title, target.name),
            consequences = listOf(stringResource(R.string.admin_revoke_consequence), stringResource(R.string.admin_revoke_consequence_2)),
            hold = stringResource(R.string.admin_revoke_hold),
            onConfirm = {
                revoke = null
                actions.onRevokeDevice(target.id)
            },
            onDismiss = { revoke = null },
        )
    }
}

@Composable
private fun DeviceRow(d: AdminDevice, self: Boolean, busy: Boolean, onTest: () -> Unit, onRevoke: () -> Unit) {
    var menu by rememberSaveable(d.id, LocalAppLock.current.epoch) { mutableStateOf(false) }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .padding(start = NekoTokens.Spacing.l, end = NekoTokens.Spacing.xs, top = NekoTokens.Spacing.s, bottom = NekoTokens.Spacing.s)
            .testTag("admin_device_${d.id}"),
    ) {
        RowIcon(NekoIcons.CoverScreen)
        Spacer(Modifier.width(NekoTokens.Spacing.l))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(NekoTokens.Spacing.xxs)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(NekoTokens.Spacing.s)) {
                Text(d.name.ifBlank { d.model }, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                if (self) StatusPill(stringResource(R.string.admin_device_self), StatusTone.Brand)
                if (d.revoked) StatusPill(stringResource(R.string.admin_device_revoked), StatusTone.Error)
                else if (d.suspended) StatusPill(stringResource(R.string.admin_device_suspended), StatusTone.Warning)
            }
            Text(
                listOf(roleName(d.role), d.model).filter(String::isNotBlank).joinToString(" · "),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (!d.revoked) {
            Box {
                IconButton(onClick = { menu = true }, enabled = !busy, modifier = Modifier.testTag("admin_device_menu_${d.id}")) {
                    Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.inbox_more))
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text(stringResource(R.string.admin_test_push)) }, onClick = { menu = false; onTest() })
                    if (!self) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.admin_revoke), color = MaterialTheme.colorScheme.error) },
                            onClick = { menu = false; onRevoke() },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun roleName(role: DeviceRole): String = stringResource(
    when (role) {
        DeviceRole.Full -> R.string.role_full
        DeviceRole.Receiver -> R.string.role_receiver
        DeviceRole.Sender -> R.string.role_sender
        DeviceRole.Other -> R.string.role_other
    },
)

// ---------------------------------------------------------------- 添加设备

/** The new device's role and name, then the pairing desk (⑬). */
@Composable
private fun AddDevicePage(ui: AdminUi, actions: AdminActions, tabletop: Boolean, onBack: () -> Unit) {
    val pairing = ui.pairing
    if (pairing != null) {
        Page(stringResource(R.string.admin_add_device), onBack, "admin_pairing") {
            PairingDeskContent(pairing, showQrHere = !tabletop, onChoose = actions.onChoosePairing, onCopy = actions.onCopySecret, onDone = onBack)
        }
        return
    }
    LaunchedEffect(Unit) { actions.onLoad(AdminPage.Sources) }
    val defaultName = stringResource(R.string.admin_new_device_name)
    var name by rememberSaveable { mutableStateOf(defaultName) }
    var role by rememberSaveable { mutableStateOf(PairingRole.Receiver) }
    var source by rememberSaveable { mutableStateOf<String?>(null) }
    var advanced by rememberSaveable { mutableStateOf(false) }
    Page(stringResource(R.string.admin_add_device), onBack, "admin_add") {
        Feedback(ui, actions)
        Text(stringResource(R.string.admin_add_intro), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedTextField(
            name,
            { name = it.take(64) },
            label = { Text(stringResource(R.string.admin_new_device_label)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
            modifier = Modifier.fillMaxWidth().testTag("admin_add_name"),
        )
        SettingsSection(stringResource(R.string.admin_role))
        val roles = listOf(PairingRole.Receiver, PairingRole.Sender, PairingRole.Full)
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            roles.forEachIndexed { i, r ->
                SegmentedButton(
                    selected = role == r,
                    onClick = { role = r },
                    shape = SegmentedButtonDefaults.itemShape(i, roles.size),
                    modifier = Modifier.testTag("admin_role_${r.name}"),
                ) { Text(stringResource(roleLabel(r))) }
            }
        }
        FootNote(stringResource(roleDetail(role)))
        if (role != PairingRole.Receiver) {
            SettingsSection(stringResource(R.string.admin_source))
            SourcePicker(ui.sources.orEmpty(), source, advanced, onSource = { source = it }, onAdvanced = { advanced = it })
        }
        val sourceOk = role == PairingRole.Receiver || source == null || SourceId.matches(source.orEmpty())
        Button(
            enabled = !ui.busy && name.isNotBlank() && sourceOk,
            onClick = { actions.onAddDevice(name.trim(), role, if (role == PairingRole.Receiver) null else source) },
            modifier = Modifier.fillMaxWidth().heightIn(min = NekoTokens.Touch.coverPrimaryTarget).testTag("admin_add_create"),
        ) {
            if (ui.busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp) else Text(stringResource(R.string.admin_add_create), style = MaterialTheme.typography.titleMedium)
        }
        FootNote(stringResource(R.string.admin_add_reauth))
    }
}

internal val SourceId = Regex("[a-z0-9][a-z0-9_-]{0,31}")

/** Existing sources as chips, or 「新来源」 (created when the pairing completes); a typed ID only under 高级. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SourcePicker(sources: List<AdminSource>, selected: String?, advanced: Boolean, onSource: (String?) -> Unit, onAdvanced: (Boolean) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(NekoTokens.Spacing.s), verticalArrangement = Arrangement.spacedBy(NekoTokens.Spacing.s)) {
        FilterChip(selected = selected == null, onClick = { onSource(null) }, label = { Text(stringResource(R.string.admin_source_new)) })
        sources.filter { it.enabled }.forEach { s ->
            FilterChip(selected = selected == s.id, onClick = { onSource(s.id) }, label = { Text(s.name.ifBlank { s.id }) })
        }
    }
    if (advanced) {
        OutlinedTextField(
            selected.orEmpty(),
            { onSource(it.lowercase().take(32).ifBlank { null }) },
            label = { Text(stringResource(R.string.admin_source_id)) },
            singleLine = true,
            textStyle = NekoTextStyles.mono,
            isError = selected != null && !SourceId.matches(selected),
            modifier = Modifier.fillMaxWidth(),
        )
    } else {
        TextButton(onClick = { onAdvanced(true) }) { Text(stringResource(R.string.admin_source_advanced)) }
    }
}

private fun roleLabel(r: PairingRole) = when (r) {
    PairingRole.Receiver -> R.string.role_receiver
    PairingRole.Sender -> R.string.role_sender
    PairingRole.Full -> R.string.role_full
}

private fun roleDetail(r: PairingRole) = when (r) {
    PairingRole.Receiver -> R.string.role_receiver_detail
    PairingRole.Sender -> R.string.role_sender_detail
    PairingRole.Full -> R.string.role_full_detail
}

// ---------------------------------------------------------------- 来源

@Composable
private fun SourcesPage(ui: AdminUi, actions: AdminActions, onBack: () -> Unit) {
    val epoch = LocalAppLock.current.epoch
    var danger by rememberSaveable(epoch) { mutableStateOf<String?>(null) }
    var creating by rememberSaveable { mutableStateOf(false) }
    Page(stringResource(R.string.admin_sources), onBack, "admin_sources") {
        Feedback(ui, actions)
        val list = ui.sources
        when {
            list == null -> Loading()
            list.isEmpty() -> EmptyState(stringResource(R.string.admin_sources_empty), stringResource(R.string.admin_sources_detail), showCat = false)
            else -> SettingsGroup {
                list.forEachIndexed { i, s ->
                    if (i > 0) GroupDivider()
                    SourceRow(
                        s,
                        busy = ui.busy,
                        onToggle = { actions.onSetSourceEnabled(s.id, !s.enabled) },
                        onRotate = { danger = "rotate:" + s.id },
                        onDelete = { danger = "delete:" + s.id },
                    )
                }
            }
        }
        if (creating) {
            NewSourceForm(busy = ui.busy, onCreate = { id, name -> creating = false; actions.onCreateSource(id, name) }, onCancel = { creating = false })
        } else {
            FilledTonalButton(onClick = { creating = true }, modifier = Modifier.heightIn(min = NekoTokens.Touch.minTarget)) {
                Icon(Icons.Filled.Add, contentDescription = null)
                Spacer(Modifier.width(NekoTokens.Spacing.s))
                Text(stringResource(R.string.admin_source_create))
            }
        }
    }
    val pending = danger
    if (pending != null) {
        val (kind, id) = pending.split(":", limit = 2).let { it[0] to it.getOrElse(1) { "" } }
        val name = ui.sources?.firstOrNull { it.id == id }?.name ?: id
        DangerDialog(
            title = stringResource(if (kind == "rotate") R.string.admin_rotate_title else R.string.admin_delete_title, name),
            consequences = listOf(stringResource(if (kind == "rotate") R.string.admin_rotate_consequence else R.string.admin_delete_consequence)),
            hold = stringResource(if (kind == "rotate") R.string.admin_rotate_hold else R.string.admin_delete_hold),
            onConfirm = {
                danger = null
                if (kind == "rotate") actions.onRotateSource(id) else actions.onDeleteSource(id)
            },
            onDismiss = { danger = null },
        )
    }
}

@Composable
private fun SourceRow(s: AdminSource, busy: Boolean, onToggle: () -> Unit, onRotate: () -> Unit, onDelete: () -> Unit) {
    var menu by rememberSaveable(s.id, LocalAppLock.current.epoch) { mutableStateOf(false) }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(start = NekoTokens.Spacing.l, end = NekoTokens.Spacing.xs),
    ) {
        RowIcon(NekoIcons.PostBox)
        Spacer(Modifier.width(NekoTokens.Spacing.l))
        Column(Modifier.weight(1f)) {
            Text(s.name.ifBlank { s.id }, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(s.id, style = NekoTextStyles.mono, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (!s.enabled) StatusPill(stringResource(R.string.admin_source_disabled), StatusTone.Neutral)
        Box {
            IconButton(onClick = { menu = true }, enabled = !busy) {
                Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.inbox_more))
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(
                    text = { Text(stringResource(if (s.enabled) R.string.admin_source_disable else R.string.admin_source_enable)) },
                    onClick = { menu = false; onToggle() },
                )
                DropdownMenuItem(text = { Text(stringResource(R.string.admin_source_rotate)) }, onClick = { menu = false; onRotate() })
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.admin_source_delete), color = MaterialTheme.colorScheme.error) },
                    onClick = { menu = false; onDelete() },
                )
            }
        }
    }
}

@Composable
private fun NewSourceForm(busy: Boolean, onCreate: (String, String) -> Unit, onCancel: () -> Unit) {
    var id by rememberSaveable { mutableStateOf("") }
    var name by rememberSaveable { mutableStateOf("") }
    SettingsGroup {
        Column(Modifier.padding(NekoTokens.Spacing.l), verticalArrangement = Arrangement.spacedBy(NekoTokens.Spacing.s)) {
            OutlinedTextField(name, { name = it.take(64) }, label = { Text(stringResource(R.string.admin_source_name)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(
                id,
                { id = it.lowercase().take(32) },
                label = { Text(stringResource(R.string.admin_source_id)) },
                singleLine = true,
                textStyle = NekoTextStyles.mono,
                isError = id.isNotEmpty() && !SourceId.matches(id),
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(NekoTokens.Spacing.s)) {
                Button(enabled = !busy && SourceId.matches(id), onClick = { onCreate(id, name.ifBlank { id }) }) { Text(stringResource(R.string.admin_source_create)) }
                TextButton(onClick = onCancel) { Text(stringResource(R.string.action_cancel)) }
            }
        }
    }
}

// ---------------------------------------------------------------- 管理凭证

@Composable
private fun KeysPage(ui: AdminUi, actions: AdminActions, onBack: () -> Unit) {
    var revoke by rememberSaveable(LocalAppLock.current.epoch) { mutableStateOf<String?>(null) }
    var name by rememberSaveable { mutableStateOf("") }
    Page(stringResource(R.string.admin_keys), onBack, "admin_keys") {
        Feedback(ui, actions)
        FootNote(stringResource(R.string.admin_keys_detail))
        val list = ui.keys
        when {
            list == null -> Loading()
            list.isEmpty() -> Unit
            else -> SettingsGroup {
                list.forEachIndexed { i, k ->
                    if (i > 0) GroupDivider()
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(start = NekoTokens.Spacing.l, end = NekoTokens.Spacing.xs),
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(k.name, style = MaterialTheme.typography.bodyLarge)
                            Text(k.prefix, style = NekoTextStyles.mono, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (AdminKeyPrefix.matches(k.prefix)) {
                            TextButton(enabled = !ui.busy, onClick = { revoke = k.prefix }) {
                                Text(stringResource(R.string.admin_revoke), color = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                }
            }
        }
        OutlinedTextField(name, { name = it.take(64) }, label = { Text(stringResource(R.string.admin_key_name)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
        FilledTonalButton(
            enabled = !ui.busy && name.isNotBlank(),
            onClick = {
                actions.onCreateKey(name.trim())
                name = ""
            },
            modifier = Modifier.heightIn(min = NekoTokens.Touch.minTarget),
        ) { Text(stringResource(R.string.admin_key_create)) }
    }
    val prefix = revoke
    if (prefix != null) {
        DangerDialog(
            title = stringResource(R.string.admin_key_revoke_title, prefix),
            consequences = listOf(stringResource(R.string.admin_key_revoke_consequence)),
            hold = stringResource(R.string.admin_revoke_hold),
            onConfirm = {
                revoke = null
                actions.onRevokeKey(prefix)
            },
            onDismiss = { revoke = null },
        )
    }
}

internal val AdminKeyPrefix = Regex("mga_[A-Za-z0-9_-]{6}")

// ---------------------------------------------------------------- 审计 / 服务器状态

@Composable
private fun AuditPage(ui: AdminUi, onBack: () -> Unit) {
    Page(stringResource(R.string.admin_audit), onBack, "admin_audit") {
        when (ui.auditOk) {
            true -> StatusPill(stringResource(R.string.admin_audit_ok), StatusTone.Success, large = true)
            false -> StatusPill(stringResource(R.string.admin_audit_broken), StatusTone.Error, large = true)
            null -> Unit
        }
        val list = ui.audit
        when {
            list == null -> Loading()
            list.isEmpty() -> EmptyState(stringResource(R.string.admin_audit_empty), "", showCat = false)
            else -> SettingsGroup {
                list.forEachIndexed { i, line ->
                    if (i > 0) GroupDivider(inset = false)
                    Column(Modifier.fillMaxWidth().padding(horizontal = NekoTokens.Spacing.l, vertical = NekoTokens.Spacing.s)) {
                        Text(line.action, style = MaterialTheme.typography.bodyLarge)
                        Text(
                            listOf(line.actor, line.result).filter(String::isNotBlank).joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

/** 状态 + 统计 + 设置 of the old management page, merged and read only. */
@Composable
private fun ServerPage(ui: AdminUi, onBack: () -> Unit) {
    Page(stringResource(R.string.admin_server), onBack, "admin_server") {
        val s = ui.server
        if (s == null) {
            Loading()
            return@Page
        }
        if (s.overall.isNotBlank()) NoticeCard(text = s.overall, tone = StatusTone.Neutral)
        if (s.components.isNotEmpty()) {
            SettingsGroup { s.components.forEach { (name, summary) -> InfoRow(name, summary) } }
        }
        SettingsGroup {
            s.received?.let { InfoRow(stringResource(R.string.admin_stat_received), it.toString()) }
            s.delivered?.let { InfoRow(stringResource(R.string.admin_stat_delivered), it.toString()) }
            s.filtered?.let { InfoRow(stringResource(R.string.admin_stat_filtered), it.toString()) }
            s.version?.let { InfoRow(stringResource(R.string.admin_server_version), it, mono = true) }
            s.timezone?.let { InfoRow(stringResource(R.string.admin_server_timezone), it) }
            s.retentionDays?.let { InfoRow(stringResource(R.string.admin_server_retention), stringResource(R.string.admin_days, it)) }
        }
    }
}

@Composable
private fun Loading() {
    Box(Modifier.fillMaxWidth().padding(NekoTokens.Spacing.xl), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
}

/** A new credential, shown once: copy (sensitive clipboard, 60 s) or confirm it was saved. */
@Composable
private fun SecretCard(secret: String, actions: AdminActions) {
    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.tertiaryContainer, modifier = Modifier.fillMaxWidth().testTag("admin_secret")) {
        Column(Modifier.padding(NekoTokens.Spacing.l), verticalArrangement = Arrangement.spacedBy(NekoTokens.Spacing.s)) {
            Text(stringResource(R.string.admin_secret_title), style = MaterialTheme.typography.titleSmall)
            Text(secret, style = NekoTextStyles.mono)
            Row(horizontalArrangement = Arrangement.spacedBy(NekoTokens.Spacing.s)) {
                FilledTonalButton(onClick = { actions.onCopySecret(secret) }) { Text(stringResource(R.string.admin_secret_copy)) }
                TextButton(onClick = actions.onDismissSecret) { Text(stringResource(R.string.admin_secret_saved)) }
            }
        }
    }
}

/**
 * Destructive operations only (吊销, 删除, 轮换): what will happen, then a
 * 1.5 s hold. Closes by itself when the app locks (its state is keyed on the lock epoch).
 */
@Composable
fun DangerDialog(title: String, consequences: List<String>, hold: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(NekoTokens.Spacing.m)) {
                consequences.forEach { Text(it, style = MaterialTheme.typography.bodyMedium) }
                HoldToConfirmButton(text = hold, onConfirm = onConfirm, modifier = Modifier.testTag("danger_hold"))
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}
