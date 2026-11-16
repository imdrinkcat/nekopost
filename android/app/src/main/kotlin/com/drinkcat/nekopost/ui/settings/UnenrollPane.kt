package com.drinkcat.nekopost.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import com.drinkcat.nekopost.R
import com.drinkcat.nekopost.core.forwarder.Enrollment
import com.drinkcat.nekopost.core.ui.component.HoldToConfirmButton
import com.drinkcat.nekopost.core.ui.component.KeyValueRow
import com.drinkcat.nekopost.core.ui.component.NekoCard
import com.drinkcat.nekopost.core.ui.token.NekoTokens
import com.drinkcat.nekopost.ui.common.Bullet
import com.drinkcat.nekopost.ui.shell.ShellActions
import com.drinkcat.nekopost.ui.shell.ShellUiState

/**
 * Danger zone (design §10.9: "Sheet 写明后果 → 长按 1.5 秒"). Serious wording,
 * no cat. The sheet is cancelled automatically if the pane leaves the screen.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UnenrollPane(state: ShellUiState, actions: ShellActions) {
    val e = state.forwarder.enrollment
    if (e == null) {
        Text(stringResource(R.string.settings_server_none), style = MaterialTheme.typography.bodyMedium)
        return
    }
    var showSheet by rememberSaveable { mutableStateOf(false) }
    NekoCard {
        KeyValueRow(stringResource(R.string.enrollment_server), e.serverHost, mono = true)
        KeyValueRow(stringResource(R.string.enrollment_source), e.sourceName)
        Text(stringResource(R.string.settings_unenroll_detail), style = MaterialTheme.typography.bodyMedium)
        OutlinedButton(
            onClick = { showSheet = true },
            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
            modifier = Modifier.heightIn(min = NekoTokens.Touch.minTarget).testTag("unenroll_open"),
        ) {
            Text(stringResource(R.string.unenroll_open))
        }
    }
    if (showSheet) {
        ModalBottomSheet(
            onDismissRequest = { showSheet = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        ) {
            UnenrollSheetContent(
                enrollment = e,
                pending = state.forwarder.queue.pending,
                onConfirm = {
                    showSheet = false
                    actions.onUnenroll()
                },
                onCancel = { showSheet = false },
            )
        }
    }
}

/** Body of the confirmation sheet; public so screenshot tests can render it without a window. */
@Composable
fun UnenrollSheetContent(enrollment: Enrollment, pending: Int, onConfirm: () -> Unit, onCancel: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = NekoTokens.Spacing.xl)
            .padding(bottom = NekoTokens.Spacing.xl),
        verticalArrangement = Arrangement.spacedBy(NekoTokens.Spacing.m),
    ) {
        Text(
            stringResource(R.string.unenroll_sheet_title, enrollment.sourceName),
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.semantics { heading() },
        )
        Bullet(stringResource(R.string.unenroll_consequence_token))
        Bullet(
            if (pending > 0) {
                stringResource(R.string.unenroll_consequence_queue, pending)
            } else {
                stringResource(R.string.unenroll_consequence_log)
            },
        )
        Bullet(stringResource(R.string.unenroll_consequence_server))
        Bullet(stringResource(R.string.unenroll_consequence_irreversible))
        HoldToConfirmButton(
            text = stringResource(R.string.unenroll_hold),
            onConfirm = onConfirm,
            modifier = Modifier.testTag("unenroll_hold"),
        )
        TextButton(onClick = onCancel, modifier = Modifier.fillMaxWidth().heightIn(min = NekoTokens.Touch.minTarget)) {
            Text(stringResource(R.string.action_cancel))
        }
    }
}
