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
 * A detail is a level only where it replaces its list: the cover display, and
 * 设置 on tabletop (one pane below the hinge). Where the selection and the list
 * are both on screen — two panes, and 消息 / 记录 on tabletop (the selection
 * above the hinge) — the selection is part of the tab and back leaves the tab.
 *
 * A page with work in progress keeps it or asks before leaving: 添加设备 and
 * 配对此手机 while a pairing waits ask 离开配对？, 发给自己 keeps its draft and
 * send in InboxMemory, and 分拣台 rescues a queued message right away.
 *
 * While the app lock hides the content nothing here reacts: back on the lock
 * screen leaves the app instead of changing the hidden screens behind it.
 */
@Composable
fun LevelBackHandler(enabled: Boolean = true, onBack: () -> Unit) {
    val hidden = LocalAppLock.current.state.hidesContent
    BackHandler(enabled = enabled && !hidden, onBack = onBack)
}
