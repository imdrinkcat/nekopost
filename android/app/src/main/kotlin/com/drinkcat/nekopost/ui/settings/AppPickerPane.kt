package com.drinkcat.nekopost.ui.settings

import androidx.compose.foundation.Image
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
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.drinkcat.nekopost.R
import com.drinkcat.nekopost.core.forwarder.NotificationAppPolicy
import com.drinkcat.nekopost.core.ui.theme.NekoTextStyles
import com.drinkcat.nekopost.core.ui.token.NekoTokens
import com.drinkcat.nekopost.system.InstalledApp
import com.drinkcat.nekopost.system.rememberAppIcon
import com.drinkcat.nekopost.ui.shell.ShellActions
import com.drinkcat.nekopost.ui.shell.ShellMemory
import com.drinkcat.nekopost.ui.shell.ShellUiState

/**
 * Notification app picker (design §8.5): launcher apps only (MAIN/LAUNCHER
 * `<queries>`), nothing selected by default, Telegram clients and this app
 * shown but disabled with the reason.
 */
@Composable
fun AppPickerPane(state: ShellUiState, actions: ShellActions, memory: ShellMemory) {
    val f = state.forwarder
    val selected = f.notificationApps
    val apps = state.apps
    val query = memory.appQuery.trim()

    Column(Modifier.fillMaxSize()) {
        Column(
            Modifier.padding(horizontal = NekoTokens.Spacing.l),
            verticalArrangement = Arrangement.spacedBy(NekoTokens.Spacing.s),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 56.dp)
                    .toggleable(
                        value = f.notificationsEnabled,
                        role = Role.Switch,
                        onValueChange = actions.onNotificationsEnabledChange,
                    ),
            ) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.apps_master), style = MaterialTheme.typography.titleMedium)
                    Text(
                        stringResource(R.string.apps_master_detail, selected.size),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = f.notificationsEnabled, onCheckedChange = null)
            }
            OutlinedTextField(
                value = memory.appQuery,
                onValueChange = { memory.appQuery = it },
                singleLine = true,
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                trailingIcon = {
                    if (memory.appQuery.isNotEmpty()) {
                        IconButton(onClick = { memory.appQuery = "" }) {
                            Icon(Icons.Filled.Clear, contentDescription = stringResource(R.string.action_clear))
                        }
                    }
                },
                placeholder = { Text(stringResource(R.string.apps_search)) },
                modifier = Modifier.fillMaxWidth().testTag("apps_search"),
            )
        }

        if (apps == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        } else {
            AppList(apps, selected, query, actions)
        }
    }
}

@Composable
private fun AppList(apps: List<InstalledApp>, selected: Set<String>, query: String, actions: ShellActions) {
    run {
        val known = apps.associateBy { it.packageName }
        // Selected packages that are no longer installed stay visible so they can be removed.
        val all = apps + selected.filter { it !in known }.map { InstalledApp(it, it) }
        val visible = all.filter {
            query.isEmpty() || it.label.contains(query, ignoreCase = true) || it.packageName.contains(query, ignoreCase = true)
        }
        val (blocked, selectable) = visible.partition { !NotificationAppPolicy.isSelectable(it.packageName) }
        val (chosen, others) = selectable.partition { it.packageName in selected }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(NekoTokens.Spacing.l),
            verticalArrangement = Arrangement.spacedBy(NekoTokens.Spacing.xs),
        ) {
            if (chosen.isNotEmpty()) {
                item(key = "h-chosen") { SectionTitle(stringResource(R.string.apps_section_selected, chosen.size)) }
                items(chosen, key = { "c-" + it.packageName }) { app ->
                    AppRow(app, checked = true, enabled = true) { actions.onNotificationAppsChange(selected - app.packageName) }
                }
            }
            if (others.isNotEmpty()) {
                item(key = "h-others") { SectionTitle(stringResource(R.string.apps_section_others)) }
                items(others, key = { "o-" + it.packageName }) { app ->
                    AppRow(app, checked = false, enabled = true) { actions.onNotificationAppsChange(selected + app.packageName) }
                }
            }
            if (blocked.isNotEmpty()) {
                item(key = "h-blocked") {
                    Column {
                        SectionTitle(stringResource(R.string.apps_section_blocked))
                        Text(
                            stringResource(R.string.apps_blocked_reason),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(bottom = NekoTokens.Spacing.xs),
                        )
                    }
                }
                items(blocked, key = { "b-" + it.packageName }) { app ->
                    AppRow(app, checked = false, enabled = false) {}
                }
            }
            if (visible.isEmpty()) {
                item(key = "none") {
                    Text(
                        stringResource(R.string.apps_none_found),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = NekoTokens.Spacing.xl),
                    )
                }
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier
            .padding(top = NekoTokens.Spacing.m, bottom = NekoTokens.Spacing.xs, start = NekoTokens.Spacing.xs)
            .semantics { heading() },
    )
}

@Composable
private fun AppRow(app: InstalledApp, checked: Boolean, enabled: Boolean, onToggle: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.small)
            .toggleable(value = checked, enabled = enabled, role = Role.Checkbox, onValueChange = { onToggle() })
            .heightIn(min = 60.dp)
            .padding(horizontal = NekoTokens.Spacing.s)
            .testTag("app_${app.packageName}"),
    ) {
        AppIcon(app)
        Spacer(Modifier.width(NekoTokens.Spacing.m))
        Column(Modifier.weight(1f)) {
            Text(
                app.label,
                style = MaterialTheme.typography.titleSmall,
                color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                app.packageName,
                style = NekoTextStyles.mono.copy(fontSize = MaterialTheme.typography.labelSmall.fontSize),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Checkbox(checked = checked, onCheckedChange = null, enabled = enabled)
    }
}

/** The real launcher icon when available, otherwise a letter avatar. */
@Composable
private fun AppIcon(app: InstalledApp) {
    val icon = rememberAppIcon(app.packageName)
    if (icon != null) {
        Image(bitmap = icon, contentDescription = null, modifier = Modifier.size(40.dp))
    } else {
        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.tertiaryContainer, modifier = Modifier.size(40.dp)) {
            Box(contentAlignment = Alignment.Center) {
                Text(
                    app.label.take(1).uppercase(),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                )
            }
        }
    }
}
