package com.drinkcat.nekopost.ui.common

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.drinkcat.nekopost.R
import com.drinkcat.nekopost.core.ui.theme.NekoTextStyles
import com.drinkcat.nekopost.core.ui.theme.NekopostTheme
import com.drinkcat.nekopost.core.ui.theme.StatusTone
import com.drinkcat.nekopost.core.ui.token.NekoTokens

/*
 * The settings vocabulary (one look for every settings page): a section label,
 * a group container, and rows that are at least 56dp tall and clickable or
 * toggleable as a whole.
 */

@Composable
fun SettingsSection(label: String, modifier: Modifier = Modifier) {
    Text(
        label,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier
            .padding(start = NekoTokens.Spacing.l, top = NekoTokens.Spacing.l, bottom = NekoTokens.Spacing.s)
            .semantics { heading() },
    )
}

/** Rows on one tonal container, separated by inset dividers ([GroupDivider]). */
@Composable
fun SettingsGroup(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(vertical = NekoTokens.Spacing.xs), content = content)
    }
}

@Composable
fun GroupDivider(inset: Boolean = true) {
    HorizontalDivider(
        modifier = Modifier.padding(start = if (inset) 72.dp else NekoTokens.Spacing.l, end = NekoTokens.Spacing.l),
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
    )
}

/** A 24dp icon in a 40dp tonal circle. */
@Composable
fun RowIcon(icon: ImageVector, danger: Boolean = false) {
    Surface(
        shape = CircleShape,
        color = if (danger) MaterialTheme.colorScheme.surfaceContainerHighest else MaterialTheme.colorScheme.secondaryContainer,
        contentColor = if (danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSecondaryContainer,
        modifier = Modifier.size(40.dp),
    ) {
        Box(contentAlignment = Alignment.Center) { Icon(icon, contentDescription = null, modifier = Modifier.size(22.dp)) }
    }
}

@Composable
private fun RowText(title: String, summary: String?, modifier: Modifier, danger: Boolean = false, summaryLines: Int = 1) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(NekoTokens.Spacing.xxs)) {
        Text(
            title,
            style = MaterialTheme.typography.bodyLarge,
            color = if (danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
        )
        if (!summary.isNullOrBlank()) {
            Text(
                summary,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = summaryLines,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** Opens a page: icon, title, one supporting line, chevron. */
@Composable
fun NavRow(
    title: String,
    summary: String? = null,
    icon: ImageVector? = null,
    selected: Boolean = false,
    danger: Boolean = false,
    trailing: (@Composable () -> Unit)? = null,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .padding(horizontal = NekoTokens.Spacing.xs)
            .then(
                if (selected) {
                    Modifier.padding(vertical = NekoTokens.Spacing.xxs)
                } else {
                    Modifier
                },
            )
            .selectable(selected = selected, role = Role.Button, onClick = onClick),
    ) {
        Surface(
            color = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerLow,
            shape = MaterialTheme.shapes.medium,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.heightIn(min = 56.dp).padding(horizontal = NekoTokens.Spacing.m, vertical = NekoTokens.Spacing.s),
            ) {
                if (icon != null) {
                    RowIcon(icon, danger)
                    Spacer(Modifier.width(NekoTokens.Spacing.l))
                }
                RowText(title, summary, Modifier.weight(1f), danger)
                if (trailing != null) {
                    trailing()
                } else if (!danger) {
                    Icon(
                        Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.outline,
                    )
                }
            }
        }
    }
}

@Composable
fun SwitchRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    summary: String? = null,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onCheckedChange)
            .padding(horizontal = NekoTokens.Spacing.l, vertical = NekoTokens.Spacing.s),
    ) {
        if (icon != null) {
            RowIcon(icon)
            Spacer(Modifier.width(NekoTokens.Spacing.l))
        }
        RowText(title, summary, Modifier.weight(1f), summaryLines = 3)
        Spacer(Modifier.width(NekoTokens.Spacing.m))
        Switch(checked = checked, onCheckedChange = null, enabled = enabled)
    }
}

/** A choice among a few values; the current one is shown on the right and picked in a dialog. */
@Composable
fun <T> ValueRow(
    title: String,
    value: T,
    choices: List<T>,
    label: @Composable (T) -> String,
    onChange: (T) -> Unit,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
) {
    var open by rememberSaveable { mutableStateOf(false) }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clickable(enabled = enabled, role = Role.Button) { open = true }
            .padding(horizontal = NekoTokens.Spacing.l, vertical = NekoTokens.Spacing.s),
    ) {
        Text(
            title,
            style = MaterialTheme.typography.bodyLarge,
            color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(NekoTokens.Spacing.m))
        Text(
            label(value),
            style = MaterialTheme.typography.labelLarge,
            color = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    if (open) {
        AlertDialog(
            onDismissRequest = { open = false },
            title = { Text(title) },
            text = {
                Column(Modifier.selectableGroup()) {
                    choices.forEach { choice ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = NekoTokens.Touch.minTarget)
                                .selectable(selected = choice == value, role = Role.RadioButton) {
                                    onChange(choice)
                                    open = false
                                },
                        ) {
                            RadioButton(selected = choice == value, onClick = null)
                            Spacer(Modifier.width(NekoTokens.Spacing.m))
                            Text(label(choice), style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { open = false }) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }
}

/** Label and value; [mono] for hosts and IDs. */
@Composable
fun InfoRow(label: String, value: String, mono: Boolean = false, modifier: Modifier = Modifier) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .padding(horizontal = NekoTokens.Spacing.l, vertical = NekoTokens.Spacing.s),
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(0.42f),
        )
        Text(
            value,
            style = if (mono) NekoTextStyles.mono else MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.End,
            modifier = Modifier.weight(0.58f),
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Plain explanatory text inside or below a group. */
@Composable
fun FootNote(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.padding(horizontal = NekoTokens.Spacing.l, vertical = NekoTokens.Spacing.s),
    )
}

/** A danger action inside a group: error-coloured text, no chevron. */
@Composable
fun DangerRow(title: String, summary: String? = null, icon: ImageVector? = null, modifier: Modifier = Modifier, onClick: () -> Unit) {
    NavRow(title = title, summary = summary, icon = icon, danger = true, modifier = modifier, onClick = onClick)
}

/** A tonal notice (warning / info) with an optional action. */
@Composable
fun NoticeCard(
    text: String,
    tone: StatusTone,
    modifier: Modifier = Modifier,
    title: String? = null,
    action: String? = null,
    onAction: (() -> Unit)? = null,
    actionTag: String? = null,
) {
    val colors = NekopostTheme.colors.tone(tone)
    Surface(shape = MaterialTheme.shapes.medium, color = colors.container, contentColor = colors.content, modifier = modifier.fillMaxWidth()) {
        Column(Modifier.padding(NekoTokens.Spacing.l), verticalArrangement = Arrangement.spacedBy(NekoTokens.Spacing.s)) {
            if (title != null) Text(title, style = MaterialTheme.typography.titleSmall)
            Text(text, style = MaterialTheme.typography.bodyMedium)
            if (action != null && onAction != null) {
                TextButton(
                    onClick = onAction,
                    modifier = Modifier
                        .heightIn(min = NekoTokens.Touch.minTarget)
                        .then(if (actionTag != null) Modifier.testTag(actionTag) else Modifier),
                ) { Text(action) }
            }
        }
    }
}
