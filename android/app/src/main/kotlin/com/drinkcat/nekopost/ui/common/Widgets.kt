package com.drinkcat.nekopost.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.drinkcat.nekopost.R
import com.drinkcat.nekopost.core.ui.theme.NekoTextStyles
import com.drinkcat.nekopost.core.ui.theme.NekopostTheme
import com.drinkcat.nekopost.core.ui.theme.StatusTone
import com.drinkcat.nekopost.core.ui.token.NekoTokens

/** An icon in a tonal circle, coloured by [tone]. */
@Composable
fun ToneIcon(icon: ImageVector, tone: StatusTone, modifier: Modifier = Modifier, size: Dp = 48.dp) {
    val colors = NekopostTheme.colors.tone(tone)
    Surface(shape = CircleShape, color = colors.container, contentColor = colors.content, modifier = modifier.size(size)) {
        Box(contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(size * 0.5f))
        }
    }
}

/** Screen or pane title with an optional supporting line. */
@Composable
fun PaneHeader(title: String, subtitle: String? = null, modifier: Modifier = Modifier) {
    Column(modifier.padding(bottom = NekoTokens.Spacing.xs), verticalArrangement = Arrangement.spacedBy(NekoTokens.Spacing.xxs)) {
        Text(title, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { heading() })
        if (subtitle != null) {
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/**
 * A settings path such as 设置 › 应用 › Nekopost › ⋮ › 允许受限设置, rendered as
 * chips so it scans well and wraps on the cover display. TalkBack reads it as
 * one sentence.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PathSteps(steps: List<String>, modifier: Modifier = Modifier) {
    val spoken = steps.joinToString("，")
    FlowRow(
        modifier = modifier.clearAndSetSemantics { contentDescription = spoken },
        horizontalArrangement = Arrangement.spacedBy(NekoTokens.Spacing.xs),
        verticalArrangement = Arrangement.spacedBy(NekoTokens.Spacing.xs),
        itemVerticalAlignment = Alignment.CenterVertically,
    ) {
        steps.forEachIndexed { i, s ->
            val last = i == steps.lastIndex
            Surface(
                shape = MaterialTheme.shapes.extraSmall,
                color = if (last) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHighest,
                contentColor = if (last) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
            ) {
                Text(s, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
            }
            if (!last) {
                Text("›", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** A quiet card with a lightbulb-style hint: used for One UI guidance. */
@Composable
fun GuidanceCard(title: String, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.55f),
        contentColor = MaterialTheme.colorScheme.onSurface,
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(NekoTokens.Spacing.l), verticalArrangement = Arrangement.spacedBy(NekoTokens.Spacing.s)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Info, contentDescription = null, tint = MaterialTheme.colorScheme.secondary, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(NekoTokens.Spacing.s))
                Text(title, style = MaterialTheme.typography.titleSmall)
            }
            content()
        }
    }
}

/** A short bulleted paragraph. */
@Composable
fun Bullet(text: String, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth()) {
        Text("•", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(NekoTokens.Spacing.s))
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}

/** One permission/check line: ✅ or ⚠️ icon, label, and the state as text (never colour alone). */
@Composable
fun CheckLine(label: String, ok: Boolean, modifier: Modifier = Modifier, okText: String? = null, missingText: String? = null) {
    val tone = NekopostTheme.colors.tone(if (ok) StatusTone.Success else StatusTone.Warning)
    Row(modifier.fillMaxWidth().heightIn(min = 32.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(
            imageVector = if (ok) Icons.Filled.CheckCircle else Icons.Filled.Warning,
            contentDescription = null,
            tint = tone.content,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(NekoTokens.Spacing.m))
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Text(
            text = if (ok) okText ?: stringResource(R.string.perm_granted) else missingText ?: stringResource(R.string.perm_missing),
            style = MaterialTheme.typography.labelMedium,
            color = tone.content,
        )
    }
}

/** Monospace text on a sunken surface (sample SMS, IDs). */
@Composable
fun MonoBlock(text: String, modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.surfaceContainerHighest) {
    Surface(shape = MaterialTheme.shapes.small, color = color, modifier = modifier) {
        Text(
            text,
            style = NekoTextStyles.mono.copy(fontSize = MaterialTheme.typography.bodyLarge.fontSize),
            modifier = Modifier.padding(horizontal = NekoTokens.Spacing.m, vertical = NekoTokens.Spacing.s),
        )
    }
}
