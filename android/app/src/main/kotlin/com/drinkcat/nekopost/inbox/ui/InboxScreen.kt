package com.drinkcat.nekopost.inbox.ui

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import com.drinkcat.nekopost.lock.LocalAppLock
import com.drinkcat.nekopost.core.ui.fold.FoldState
import com.drinkcat.nekopost.core.ui.fold.HingeAwareSplit
import com.drinkcat.nekopost.core.ui.fold.SplitAxis
import com.drinkcat.nekopost.ui.common.AdaptiveListDetail
import com.drinkcat.nekopost.ui.common.LevelBackHandler
import com.drinkcat.nekopost.ui.shell.PaneMode

/**
 * 消息 (stateless). Cover: the list, a message opens full screen. Inner
 * display: list and message side by side. Tabletop: the open message above
 * the hinge, the list (and the fold tools) below it. Keyboard (§10.5): j/k,
 * Enter, Esc, c copies the code, e toggles read, Ctrl/⌘+F searches, Ctrl/⌘+K
 * opens the tools menu. Long-press starts 多选; back, Esc or × leaves it
 * (on a single pane an open message closes first).
 */
@Composable
fun InboxScreen(
    state: InboxUiState,
    fold: FoldState,
    mode: PaneMode,
    actions: InboxActions,
    listState: LazyListState = rememberLazyListState(),
    modifier: Modifier = Modifier,
) {
    val searchFocus = remember { FocusRequester() }
    var searchFocused by remember { mutableStateOf(false) }
    // Popups are separate windows: close them whenever the app locks.
    var menuOpen by remember(LocalAppLock.current.epoch) { mutableStateOf(false) }
    val rows = state.visibleRows
    val leavesSelection = state.selecting && (mode != PaneMode.Single || state.selectedId == null)

    // ① 展开即达 and notification taps: the open message is scrolled into view.
    LaunchedEffect(state.selectedId, mode) {
        val id = state.selectedId ?: return@LaunchedEffect
        if (mode == PaneMode.Single) return@LaunchedEffect
        val index = rows.indexOfFirst { it.id == id }
        if (index >= 0 && listState.layoutInfo.visibleItemsInfo.none { it.key == id }) listState.animateScrollToItem(index)
    }

    val keys = Modifier.onPreviewKeyEvent { event ->
        if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
        val command = event.isCtrlPressed || event.isMetaPressed
        if (command) {
            when (event.key) {
                Key.F -> { searchFocus.requestFocus(); true }
                Key.K -> { menuOpen = !menuOpen; true }
                else -> false
            }
        } else if (searchFocused) {
            false
        } else {
            val index = rows.indexOfFirst { it.id == state.selectedId }
            when (event.key) {
                Key.J, Key.DirectionDown -> rows.getOrNull(index + 1)?.let { actions.onOpen(it.id); true } ?: false
                Key.K, Key.DirectionUp -> rows.getOrNull(index - 1)?.let { actions.onOpen(it.id); true } ?: false
                Key.Enter -> rows.getOrNull(index.coerceAtLeast(0))?.let { actions.onOpen(it.id); true } ?: false
                Key.Escape -> when {
                    leavesSelection -> { actions.onClearPicks(); true }
                    state.selectedId != null -> { actions.onBack(); true }
                    else -> false
                }
                Key.C -> state.detail?.takeIf { it.otp != null }?.let { actions.onCopyCode(it.id); true } ?: false
                Key.E -> state.detail?.let { actions.onSetRead(it.id, !it.read); true } ?: false
                Key.Slash -> { searchFocus.requestFocus(); true }
                else -> false
            }
        }
    }.focusable()

    val list: @Composable () -> Unit = {
        InboxListPane(state, mode, actions, listState, searchFocus, { searchFocused = it }, menuOpen, { menuOpen = it })
    }
    val detail: @Composable () -> Unit = { MessageDetailPane(state, mode, fold, actions) }
    when (mode) {
        PaneMode.TopBottom -> HingeAwareSplit(
            fold = fold,
            axis = SplitAxis.Vertical,
            modifier = modifier.fillMaxSize().then(keys).testTag("inbox_tabletop"),
            first = detail,
            second = list,
        )
        else -> AdaptiveListDetail(
            fold = fold,
            twoPane = mode == PaneMode.TwoPane,
            showDetail = state.selectedId != null,
            onBack = actions.onBack,
            list = list,
            detail = detail,
            modifier = modifier.then(keys).testTag(if (mode == PaneMode.TwoPane) "inbox_two_pane" else "inbox_single"),
        )
    }
    // On a single pane an open message hides the list: back closes the message first (AdaptiveListDetail).
    LevelBackHandler(enabled = leavesSelection, onBack = actions.onClearPicks)
}
