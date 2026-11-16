package com.drinkcat.nekopost.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.drinkcat.nekopost.R
import com.drinkcat.nekopost.core.ui.component.EmptyState
import com.drinkcat.nekopost.core.ui.component.NekoCard
import com.drinkcat.nekopost.core.ui.fold.FoldState
import com.drinkcat.nekopost.core.ui.fold.HingeAwareSplit
import com.drinkcat.nekopost.core.ui.fold.SplitAxis
import com.drinkcat.nekopost.core.ui.token.NekoTokens
import com.drinkcat.nekopost.ui.forward.EnrollmentCard
import com.drinkcat.nekopost.ui.shell.PaneMode
import com.drinkcat.nekopost.ui.shell.ShellActions
import com.drinkcat.nekopost.ui.shell.ShellUiState

private enum class SettingsItem(val title: Int, val icon: ImageVector) {
    Server(R.string.settings_server, Icons.Filled.Phone),
    Apps(R.string.settings_apps, Icons.Filled.Notifications),
    Security(R.string.settings_security, Icons.Filled.Lock),
    Unenroll(R.string.settings_unenroll, Icons.Filled.Delete),
    About(R.string.settings_about, Icons.Filled.Info),
}

/**
 * 设置. M0: the structure only; each entry shows what it will control.
 *  - Single: list with inline summaries.
 *  - TwoPane: list + detail.
 *  - TopBottom: detail above the hinge, list below.
 */
@Composable
fun SettingsScreen(state: ShellUiState, fold: FoldState, mode: PaneMode, actions: ShellActions) {
    var selectedName by rememberSaveable { mutableStateOf(SettingsItem.Server.name) }
    val selected = SettingsItem.valueOf(selectedName)
    val items = SettingsItem.entries.filter { it != SettingsItem.Unenroll || state.forwarder.isEnrolled }
    val onSelect: (SettingsItem) -> Unit = { selectedName = it.name }

    when (mode) {
        PaneMode.Single -> LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(NekoTokens.Spacing.l),
            verticalArrangement = Arrangement.spacedBy(NekoTokens.Spacing.s),
        ) {
            item { Title() }
            items(items, key = { it.name }) { item ->
                SettingsRow(item, summary = summary(item, state), selected = false, onClick = { onSelect(item) })
            }
        }
        PaneMode.TwoPane -> HingeAwareSplit(
            fold = fold,
            axis = SplitAxis.Horizontal,
            fraction = 0.42f,
            gap = NekoTokens.Spacing.s,
            modifier = Modifier.fillMaxSize(),
            first = { SettingsList(items, selected, state, onSelect, title = true) },
            second = { SettingsDetail(selected, state, actions) },
        )
        PaneMode.TopBottom -> HingeAwareSplit(
            fold = fold,
            axis = SplitAxis.Vertical,
            modifier = Modifier.fillMaxSize(),
            first = { SettingsDetail(selected, state, actions = ShellActions()) },
            second = { SettingsList(items, selected, state, onSelect, title = false) },
        )
    }
}

@Composable
private fun Title() {
    Text(
        stringResource(R.string.settings_title),
        style = MaterialTheme.typography.headlineSmall,
        modifier = Modifier.padding(bottom = NekoTokens.Spacing.s),
    )
}

@Composable
private fun SettingsList(
    items: List<SettingsItem>,
    selected: SettingsItem,
    state: ShellUiState,
    onSelect: (SettingsItem) -> Unit,
    title: Boolean,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(NekoTokens.Spacing.l),
        verticalArrangement = Arrangement.spacedBy(NekoTokens.Spacing.s),
    ) {
        if (title) item { Title() }
        items(items, key = { it.name }) { item ->
            SettingsRow(item, summary = summary(item, state), selected = item == selected, onClick = { onSelect(item) })
        }
    }
}

@Composable
private fun summary(item: SettingsItem, state: ShellUiState): String = when (item) {
    SettingsItem.Server -> state.forwarder.enrollment?.let { "${it.sourceName} · ${it.serverHost}" }
        ?: stringResource(R.string.settings_server_none)
    SettingsItem.Apps -> stringResource(R.string.switch_notifications_detail, state.forwarder.notificationApps.size)
    SettingsItem.Security -> stringResource(R.string.settings_security_detail)
    SettingsItem.Unenroll -> stringResource(R.string.settings_unenroll_detail)
    SettingsItem.About -> stringResource(R.string.settings_about_detail, state.appVersion)
}

@Composable
private fun SettingsRow(item: SettingsItem, summary: String, selected: Boolean, onClick: () -> Unit) {
    val danger = item == SettingsItem.Unenroll
    Surface(
        shape = MaterialTheme.shapes.small,
        color = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
    ) {
        Row(
            modifier = Modifier
                .heightIn(min = 64.dp)
                .padding(horizontal = NekoTokens.Spacing.l, vertical = NekoTokens.Spacing.m),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = item.icon,
                contentDescription = null,
                tint = if (danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(NekoTokens.Spacing.l))
            Column(Modifier.weight(1f)) {
                Text(
                    stringResource(item.title),
                    style = MaterialTheme.typography.titleMedium,
                    color = if (danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    summary,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun SettingsDetail(item: SettingsItem, state: ShellUiState, actions: ShellActions) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(NekoTokens.Spacing.l),
        verticalArrangement = Arrangement.spacedBy(NekoTokens.Spacing.m),
    ) {
        Text(stringResource(item.title), style = MaterialTheme.typography.headlineSmall)
        when (item) {
            SettingsItem.Server -> {
                val e = state.forwarder.enrollment
                if (e != null) {
                    EnrollmentCard(e)
                } else {
                    EmptyState(
                        title = stringResource(R.string.settings_server_none),
                        message = stringResource(R.string.health_not_enrolled_detail),
                    )
                }
            }
            SettingsItem.Unenroll -> NekoCard {
                // Danger zone: plain, serious wording, no cat (design §9.1).
                Text(stringResource(R.string.settings_unenroll_detail), style = MaterialTheme.typography.bodyMedium)
                OutlinedButton(onClick = actions.onUnenroll, modifier = Modifier.heightIn(min = NekoTokens.Touch.minTarget)) {
                    Text(stringResource(R.string.settings_unenroll), color = MaterialTheme.colorScheme.error)
                }
            }
            else -> NekoCard {
                Text(summary(item, state), style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}
