@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.drinkcat.nekopost.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import com.drinkcat.nekopost.R
import com.drinkcat.nekopost.core.forwarder.Enrollment
import com.drinkcat.nekopost.core.ui.component.HoldToConfirmButton
import com.drinkcat.nekopost.core.ui.token.NekoTokens
import com.drinkcat.nekopost.ui.common.Bullet

/**
 * The one 解除配对 (design §10.9: "Sheet 写明后果 → 长按 1.5 秒"), opened straight
 * from the settings list. Its bullets follow what is removed: the forwarding
 * source, the receiving device, or both (a segmented choice when both exist).
 * Serious wording, no cat; the sheet closes by itself when the app locks.
 */
@Composable
fun UnenrollSheetContent(
    enrollment: Enrollment?,
    device: ThisDeviceUi?,
    pending: Int,
    onConfirm: (UnpairTarget) -> Unit,
    onCancel: () -> Unit,
    initialTarget: UnpairTarget? = null,
) {
    // Tapjacking (design §8.3): the sheet is its own window, so filter its
    // root view; touches that pass through another app's window are dropped.
    val view = LocalView.current
    DisposableEffect(view) {
        val root = view.rootView
        val before = root.filterTouchesWhenObscured
        root.filterTouchesWhenObscured = true
        onDispose { root.filterTouchesWhenObscured = before }
    }
    val both = enrollment != null && device != null
    var target by rememberSaveable {
        mutableStateOf(
            initialTarget ?: when {
                both -> UnpairTarget.Both
                device != null -> UnpairTarget.Device
                else -> UnpairTarget.Forwarder
            },
        )
    }
    val forwarder = enrollment != null && target != UnpairTarget.Device
    val receiver = device != null && target != UnpairTarget.Forwarder
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = NekoTokens.Spacing.xl)
            .padding(bottom = NekoTokens.Spacing.xl)
            .testTag("unenroll_sheet"),
        verticalArrangement = Arrangement.spacedBy(NekoTokens.Spacing.m),
    ) {
        Text(
            when {
                forwarder && receiver -> stringResource(R.string.unenroll_sheet_title_both)
                forwarder -> stringResource(R.string.unenroll_sheet_title, enrollment?.sourceName.orEmpty())
                else -> stringResource(R.string.unenroll_sheet_title_device, device?.name.orEmpty())
            },
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.semantics { heading() },
        )
        if (both) {
            val options = listOf(
                UnpairTarget.Device to R.string.unenroll_target_device,
                UnpairTarget.Forwarder to R.string.unenroll_target_forwarder,
                UnpairTarget.Both to R.string.unenroll_target_both,
            )
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                options.forEachIndexed { i, (value, label) ->
                    SegmentedButton(
                        selected = target == value,
                        onClick = { target = value },
                        shape = SegmentedButtonDefaults.itemShape(i, options.size),
                        modifier = Modifier.testTag("unenroll_target_${value.name}"),
                    ) { Text(stringResource(label)) }
                }
            }
        }
        if (forwarder) {
            Bullet(stringResource(R.string.unenroll_consequence_token))
            Bullet(
                if (pending > 0) {
                    stringResource(R.string.unenroll_consequence_queue, pending)
                } else {
                    stringResource(R.string.unenroll_consequence_log)
                },
            )
            Bullet(stringResource(R.string.unenroll_consequence_server))
        }
        if (receiver) {
            Bullet(stringResource(R.string.unenroll_consequence_device))
            Bullet(stringResource(R.string.unenroll_consequence_device_push))
        }
        Bullet(stringResource(R.string.unenroll_consequence_irreversible))
        HoldToConfirmButton(
            text = stringResource(R.string.unenroll_hold),
            onConfirm = { onConfirm(target) },
            modifier = Modifier.testTag("unenroll_hold"),
        )
        TextButton(onClick = onCancel, modifier = Modifier.fillMaxWidth().heightIn(min = NekoTokens.Touch.minTarget)) {
            Text(stringResource(R.string.action_cancel))
        }
    }
}
