package com.drinkcat.nekopost.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.drinkcat.nekopost.R
import com.drinkcat.nekopost.core.ui.component.KeyValueRow
import com.drinkcat.nekopost.core.ui.component.NekoCard
import com.drinkcat.nekopost.core.ui.component.SleepingCat
import com.drinkcat.nekopost.core.ui.theme.NekoTextStyles
import com.drinkcat.nekopost.core.ui.token.NekoTokens
import com.drinkcat.nekopost.system.FoldDebugInfo
import com.drinkcat.nekopost.ui.common.MonoBlock
import com.drinkcat.nekopost.ui.shell.ShellMemory
import com.drinkcat.nekopost.ui.shell.ShellUiState

/** Taps on the cat that unlock the foldable debug panel (design §9.6). */
const val DEBUG_PANEL_TAPS = 7

/** 关于: version, privacy note, and the hidden foldable debug panel (tap the cat 7 times). */
@Composable
fun AboutPane(state: ShellUiState, memory: ShellMemory, onCopy: (String) -> Unit) {
    var taps by remember { mutableIntStateOf(0) }
    val haptics = LocalHapticFeedback.current
    Column(
        Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(NekoTokens.Spacing.s),
    ) {
        val label = stringResource(R.string.about_cat_label)
        SleepingCat(
            size = 120.dp,
            modifier = Modifier
                .clip(CircleShape)
                .clickable(onClickLabel = label) {
                    taps++
                    if (taps >= DEBUG_PANEL_TAPS && state.debugPanelAvailable && !memory.debugUnlocked) {
                        memory.debugUnlocked = true
                        haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                    }
                }
                .testTag("about_cat"),
        )
        Text("Nekopost · " + stringResource(R.string.brand_name), style = MaterialTheme.typography.titleLarge)
        Text(state.appVersion, style = NekoTextStyles.mono, color = MaterialTheme.colorScheme.onSurfaceVariant)
        val remaining = DEBUG_PANEL_TAPS - taps
        when {
            memory.debugUnlocked -> Unit
            taps >= DEBUG_PANEL_TAPS && !state.debugPanelAvailable -> Text(
                stringResource(R.string.about_debug_unavailable),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            taps >= 3 -> Text(
                stringResource(R.string.about_taps_remaining, remaining),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    NekoCard {
        Text(stringResource(R.string.about_body), style = MaterialTheme.typography.bodyMedium)
        Text(
            stringResource(R.string.about_privacy),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    if (memory.debugUnlocked) {
        state.foldDebug?.let { FoldDebugPanel(it, onCopy) }
    }
}

/** Live window and fold facts for calibrating the screenshot matrix (design §10.7). */
@Composable
fun FoldDebugPanel(info: FoldDebugInfo, onCopy: (String) -> Unit) {
    NekoCard(title = stringResource(R.string.debug_title), modifier = Modifier.testTag("debug_panel")) {
        KeyValueRow(stringResource(R.string.debug_window_px), "${info.widthPx} × ${info.heightPx}", mono = true)
        KeyValueRow(stringResource(R.string.debug_window_dp), "${info.widthDp} × ${info.heightDp}", mono = true)
        KeyValueRow(stringResource(R.string.debug_density), "${info.density} (${info.densityDpi} dpi)", mono = true)
        KeyValueRow(stringResource(R.string.debug_font_scale), info.fontScale.toString(), mono = true)
        KeyValueRow(stringResource(R.string.debug_width_class), info.widthClass, mono = true)
        KeyValueRow(stringResource(R.string.debug_height_class), info.heightClass, mono = true)
        KeyValueRow(stringResource(R.string.debug_posture), info.posture, mono = true)
        KeyValueRow(stringResource(R.string.debug_hinge_angle), info.hingeAngle?.let { "$it°" } ?: "—", mono = true)
        Text(stringResource(R.string.debug_folding_feature), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        MonoBlock(info.foldingFeature ?: stringResource(R.string.debug_no_feature), modifier = Modifier.fillMaxWidth())
        MonoBlock(info.spec, modifier = Modifier.fillMaxWidth())
        FilledTonalButton(
            onClick = { onCopy(info.spec) },
            modifier = Modifier.heightIn(min = NekoTokens.Touch.minTarget).testTag("copy_spec"),
        ) {
            Text(stringResource(R.string.debug_copy_spec))
        }
    }
}
