package com.drinkcat.nekopost.lock

import android.os.CancellationSignal
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.drinkcat.nekopost.R
import com.drinkcat.nekopost.core.ui.fold.FoldState
import com.drinkcat.nekopost.core.ui.fold.Posture
import com.drinkcat.nekopost.system.AuthOutcome
import com.drinkcat.nekopost.system.DeviceAuth

/** The app lock as the content sees it (auto-read waits for unlock; danger dialogs close on lock). */
val LocalAppLock = compositionLocalOf { LockUiState(LockState.Disabled, 0) }

/** Pauses the idle timeout while true (the 值守台); a no-op outside the app. */
val LocalIdleSuspender = compositionLocalOf<(Boolean) -> Unit> { {} }

/**
 * Connects [AppLock] to an activity: shows [LockGate], opens the system prompt
 * by itself once per lock episode while this window has focus (not again after
 * the user cancelled), and reports fold-to-cover.
 */
@Composable
fun AppLockHost(lock: AppLock, fold: FoldState, content: @Composable () -> Unit) {
    val ui by lock.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val focused = LocalWindowInfo.current.isWindowFocused
    var issue by remember { mutableStateOf<LockIssue?>(null) }
    var cancelledEpoch by remember { mutableLongStateOf(-1L) }
    var signal by remember { mutableStateOf<CancellationSignal?>(null) }
    val title = stringResource(R.string.lock_prompt_title)

    fun prompt() {
        if (signal != null) return
        issue = null
        val epoch = ui.epoch
        signal = DeviceAuth.unlock(context, title, null) { outcome ->
            signal = null
            when (outcome) {
                is AuthOutcome.Success -> lock.unlocked()
                AuthOutcome.Cancelled -> cancelledEpoch = epoch
                AuthOutcome.Lockout -> issue = LockIssue.Lockout
                AuthOutcome.Unavailable -> lock.refresh()
                is AuthOutcome.Failed -> issue = LockIssue.Failed(outcome.message)
            }
        }
    }

    LaunchedEffect(ui.epoch, ui.state, focused) {
        if (ui.state is LockState.Locked && focused && cancelledEpoch != ui.epoch && issue == null) prompt()
        if (!ui.state.hidesContent) issue = null
    }
    var lastPosture by remember { mutableStateOf(fold.posture) }
    LaunchedEffect(fold.posture) {
        val wasOpen = lastPosture == Posture.Flat || lastPosture == Posture.Book || lastPosture == Posture.Tabletop
        if (wasOpen && fold.posture == Posture.Cover) lock.foldClosed()
        lastPosture = fold.posture
    }
    DisposableEffect(Unit) { onDispose { signal?.cancel() } }
    CompositionLocalProvider(LocalAppLock provides ui, LocalIdleSuspender provides lock::suspendIdle) {
        LockGate(ui.state, fold, issue, onUnlock = ::prompt) { content() }
    }
}
