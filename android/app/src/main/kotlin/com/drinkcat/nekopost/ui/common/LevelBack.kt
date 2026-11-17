package com.drinkcat.nekopost.ui.common

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import com.drinkcat.nekopost.lock.LocalAppLock

/**
 * System back (gesture, button, Esc on a keyboard) for one navigation level.
 * The app has no navigation library: every level is state (ShellMemory,
 * InboxMemory, the wizard step), and the composable that owns a level calls
 * this while the level is open. Handlers composed later win, so the innermost
 * open level is left first; with none enabled the system takes over (the
 * predictive back-to-home animation, then the activity finishes).
 *
 * Levels innermost first: dialogs, menus and sheets (Compose closes those
 * itself) → 多选 → a single-pane detail → a sub-page (an inbox tool, a page in
 * 设备与管理, a wizard step) → a tab other than the start tab → exit.
 *
 * While the app lock hides the content nothing here reacts: back on the lock
 * screen leaves the app instead of changing the hidden screens behind it.
 */
@Composable
fun LevelBackHandler(enabled: Boolean = true, onBack: () -> Unit) {
    val hidden = LocalAppLock.current.state.hidesContent
    BackHandler(enabled = enabled && !hidden, onBack = onBack)
}
