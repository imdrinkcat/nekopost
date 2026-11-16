package com.drinkcat.nekopost.core.ui.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import com.drinkcat.nekopost.core.ui.theme.NekoTextStyles
import com.drinkcat.nekopost.core.ui.token.NekoTokens

/**
 * The standard content card: tonal surface, 16dp corners, a title row with an
 * optional trailing element (usually a [StatusPill]).
 */
@Composable
fun NekoCard(
    modifier: Modifier = Modifier,
    title: String? = null,
    trailing: (@Composable () -> Unit)? = null,
    containerColor: Color = MaterialTheme.colorScheme.surfaceContainerLow,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = containerColor),
    ) {
        Column(
            modifier = Modifier.padding(NekoTokens.Spacing.l),
            verticalArrangement = Arrangement.spacedBy(NekoTokens.Spacing.m),
        ) {
            if (title != null || trailing != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (title != null) {
                        Text(
                            text = title,
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.weight(1f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    trailing?.invoke()
                }
            }
            content()
        }
    }
}

/** Small uppercase-free section label used above groups of cards. */
@Composable
fun SectionHeader(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.padding(horizontal = NekoTokens.Spacing.xs, vertical = NekoTokens.Spacing.xs),
    )
}

/** A label/value line; [mono] renders the value with tabular monospace (hosts, IDs). */
@Composable
fun KeyValueRow(label: String, value: String, modifier: Modifier = Modifier, mono: Boolean = false) {
    Row(modifier = modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(0.4f),
        )
        Text(
            text = value,
            style = if (mono) NekoTextStyles.mono else MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(0.6f),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
