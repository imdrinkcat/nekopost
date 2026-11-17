package com.drinkcat.nekopost.inbox

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.CancellationSignal
import android.view.HapticFeedbackConstants
import android.view.WindowManager
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.drinkcat.nekopost.R
import com.drinkcat.nekopost.core.ui.component.EmptyState
import com.drinkcat.nekopost.core.ui.component.StatusPill
import com.drinkcat.nekopost.core.ui.fold.FoldState
import com.drinkcat.nekopost.core.ui.fold.HingeAwareSplit
import com.drinkcat.nekopost.core.ui.fold.Posture
import com.drinkcat.nekopost.core.ui.fold.SplitAxis
import com.drinkcat.nekopost.core.ui.theme.NekoTextStyles
import com.drinkcat.nekopost.core.ui.theme.StatusTone
import com.drinkcat.nekopost.core.ui.token.NekoTokens
import com.drinkcat.nekopost.inbox.ui.SourceAvatar
import com.drinkcat.nekopost.inbox.ui.senderLabel
import com.drinkcat.nekopost.system.AuthOutcome
import com.drinkcat.nekopost.system.DeviceAuth
import com.drinkcat.nekopost.ui.common.SubScreenTopBar
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject

internal fun Context.activity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.activity()
    else -> null
}

/** ⑯ One light haptic tick when the layout switches to Book or Tabletop. */
@Composable
internal fun PostureFeedback(posture: Posture) {
    val view = LocalView.current
    var last by remember { mutableStateOf(posture) }
    LaunchedEffect(posture) {
        if (last != posture && (posture == Posture.Book || posture == Posture.Tabletop)) {
            if (Build.VERSION.SDK_INT >= 34) view.performHapticFeedback(HapticFeedbackConstants.SEGMENT_TICK)
            else view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        }
        last = posture
    }
}

/** Display half / control half: tabletop splits at the hinge, book side by side, otherwise stacked. */
@Composable
private fun DeskLayout(fold: FoldState, display: @Composable () -> Unit, controls: @Composable () -> Unit) {
    when (fold.posture) {
        Posture.Tabletop -> HingeAwareSplit(fold, SplitAxis.Vertical, Modifier.fillMaxSize(), first = display, second = controls)
        Posture.Book -> HingeAwareSplit(fold, SplitAxis.Horizontal, Modifier.fillMaxSize(), first = display, second = controls)
        else -> Column(Modifier.fillMaxSize()) {
            Box(Modifier.weight(1f)) { display() }
            controls()
        }
    }
}

// ---------------------------------------------------------------- ⑤ 分拣台

/** What the sorting desk shows (stateless; screenshot-tested). */
data class SortingUi(val item: InboxMessage?, val queued: Boolean = false, val feedback: String? = null, val loading: Boolean = false)

/**
 * 分拣台 (§10.4 ⑤): one filtered message as a big card, and three big buttons —
 * 找回并转发 (5 s undo), 确实是广告, 下一条. Swipe right to rescue, left to keep.
 */
@Composable
fun SortingDeskContent(
    ui: SortingUi,
    fold: FoldState,
    onRescue: () -> Unit,
    onKeep: () -> Unit,
    onNext: () -> Unit,
    onUndo: () -> Unit,
    onBack: () -> Unit,
) {
    val display: @Composable () -> Unit = {
        Column(
            Modifier
                .fillMaxSize()
                .pointerInput(ui.item?.id, ui.queued) {
                    var drag = 0f
                    detectHorizontalDragGestures(
                        onDragStart = { drag = 0f },
                        onHorizontalDrag = { change, amount -> change.consume(); drag += amount },
                        onDragEnd = {
                            val item = ui.item
                            if (item != null && !ui.queued) {
                                if (drag > 88.dp.toPx() && item.body != null) onRescue()
                                else if (drag < -88.dp.toPx()) onKeep()
                            }
                        },
                    )
                }
                .testTag("sorting_desk"),
        ) {
            SubScreenTopBar(stringResource(R.string.inbox_tool_sorting), onBack)
            Column(
                Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = NekoTokens.Spacing.l),
                verticalArrangement = Arrangement.spacedBy(NekoTokens.Spacing.m),
            ) {
                val item = ui.item
                if (item == null) {
                    EmptyState(
                        title = stringResource(if (ui.loading) R.string.inbox_loading else R.string.sorting_empty),
                        message = stringResource(R.string.sorting_empty_detail),
                    )
                } else {
                    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(NekoTokens.Spacing.l), verticalArrangement = Arrangement.spacedBy(NekoTokens.Spacing.s)) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(NekoTokens.Spacing.m)) {
                                SourceAvatar(item, 40.dp)
                                Column(Modifier.weight(1f)) {
                                    Text(item.senderLabel(), style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(
                                        listOf(item.sourceName, item.category).filter(String::isNotBlank).joinToString(" · "),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                            Text(item.body ?: stringResource(R.string.inbox_body_expired), style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                    Text(stringResource(R.string.sorting_swipe_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
    val controls: @Composable () -> Unit = {
        Column(
            Modifier.fillMaxWidth().padding(NekoTokens.Spacing.l),
            verticalArrangement = Arrangement.spacedBy(NekoTokens.Spacing.s),
        ) {
            ui.feedback?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
            val item = ui.item
            if (ui.queued) {
                Text(stringResource(R.string.sorting_queued), style = MaterialTheme.typography.titleMedium)
                OutlinedButton(onClick = onUndo, modifier = Modifier.fillMaxWidth().heightIn(min = NekoTokens.Touch.tabletopTarget)) {
                    Text(stringResource(R.string.sorting_undo))
                }
            } else if (item != null) {
                Button(
                    enabled = item.body != null,
                    onClick = onRescue,
                    modifier = Modifier.fillMaxWidth().heightIn(min = NekoTokens.Touch.tabletopTarget),
                ) { Text(stringResource(R.string.sorting_rescue)) }
                FilledTonalButton(onClick = onKeep, modifier = Modifier.fillMaxWidth().heightIn(min = NekoTokens.Touch.tabletopTarget)) {
                    Text(stringResource(R.string.sorting_keep))
                }
                OutlinedButton(onClick = onNext, modifier = Modifier.fillMaxWidth().heightIn(min = NekoTokens.Touch.tabletopTarget)) {
                    Text(stringResource(R.string.sorting_next))
                }
            }
        }
    }
    DeskLayout(fold, display, controls)
}

@Composable
internal fun SortingDesk(controller: DeviceInbox, fold: FoldState, onClose: () -> Unit) {
    var current by remember { mutableStateOf<InboxMessage?>(null) }
    var skipped by remember { mutableStateOf(setOf<String>()) }
    var queued by remember { mutableStateOf<InboxMessage?>(null) }
    var feedback by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }
    val failedRead = stringResource(R.string.sorting_error_read)
    val rescued = stringResource(R.string.sorting_rescued)
    val rescueFailed = stringResource(R.string.sorting_rescue_failed)
    val kept = stringResource(R.string.sorting_kept)
    LaunchedEffect(skipped) {
        loading = true
        try {
            val rows = controller.manage("GET", listOf("v1", "admin", "filtered")).optJSONArray("items")
            val id = if (rows == null) null else (0 until rows.length()).mapNotNull { rows.optJSONObject(it)?.optString("id") }
                .firstOrNull { it !in skipped && messagePattern.matches(it) }
            current = id?.let { InboxMessage.parse(controller.manage("GET", listOf("v1", "inbox", it))) }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            feedback = failedRead
        } finally {
            loading = false
        }
    }
    // Leaving (system back or the arrow) while a rescue waits out its 5 s undo window
    // does what the desk announced right away and stays to show the result.
    var rescueNow by remember { mutableStateOf(false) }
    LaunchedEffect(queued?.id) {
        val item = queued ?: return@LaunchedEffect
        withTimeoutOrNull(5000) { snapshotFlow { rescueNow }.first { it } }
        try {
            controller.manage("POST", listOf("v1", "inbox", item.id, "rescue"), JSONObject())
            feedback = rescued
            skipped = skipped + item.id
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            feedback = rescueFailed
        } finally {
            queued = null
            rescueNow = false
        }
    }
    SortingDeskContent(
        ui = SortingUi(current, queued != null, feedback, loading),
        fold = fold,
        onRescue = { queued = current },
        onKeep = { current?.let { skipped = skipped + it.id; feedback = kept } },
        onNext = { current?.let { skipped = skipped + it.id; feedback = null } },
        onUndo = { queued = null; feedback = null },
        onBack = { if (queued != null) rescueNow = true else onClose() },
    )
}

// ---------------------------------------------------------------- ④ 值守台

data class WatchUi(
    val rows: List<InboxMessage>,
    val connected: Boolean,
    val code: String? = null,
    val paused: Boolean = false,
    val keepOn: Boolean = false,
)

/**
 * 桌面值守台 (§10.4 ④): the upper half lists what arrives — a verification code
 * shows only 「验证码已到达 · 来源」 until 翻牌 (biometric) reveals it for 30 s.
 */
@Composable
fun WatchDeskContent(
    ui: WatchUi,
    fold: FoldState,
    onPause: () -> Unit,
    onKeepOn: () -> Unit,
    onFlip: () -> Unit,
    onExit: () -> Unit,
) {
    val display: @Composable () -> Unit = {
        Column(Modifier.fillMaxSize().testTag("watch_desk")) {
            SubScreenTopBar(stringResource(R.string.watch_title), onExit)
            Column(
                Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = NekoTokens.Spacing.l),
                verticalArrangement = Arrangement.spacedBy(NekoTokens.Spacing.m),
            ) {
                StatusPill(
                    stringResource(if (ui.connected) R.string.watch_connected else R.string.watch_disconnected),
                    if (ui.connected) StatusTone.Success else StatusTone.Warning,
                    large = true,
                )
                ui.code?.let {
                    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.fillMaxWidth()) {
                        Text(
                            it,
                            style = NekoTextStyles.mono.copy(fontSize = MaterialTheme.typography.displayMedium.fontSize),
                            modifier = Modifier.padding(NekoTokens.Spacing.l),
                        )
                    }
                }
                if (ui.rows.isEmpty()) {
                    Text(stringResource(R.string.inbox_empty_title), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                ui.rows.take(5).forEach { row -> WatchLine(row) }
            }
        }
    }
    val controls: @Composable () -> Unit = { WatchControls(ui, fold, onPause, onKeepOn, onFlip) }
    DeskLayout(fold, display, controls)
}

@Composable
private fun WatchLine(row: InboxMessage) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(NekoTokens.Spacing.m)) {
        SourceAvatar(row, 36.dp)
        Text(
            if (row.hasCode) {
                stringResource(R.string.watch_code_arrived, row.sourceName.ifBlank { row.category })
            } else {
                listOf(row.displaySender, row.category).filter(String::isNotBlank).joinToString(" · ")
            },
            style = MaterialTheme.typography.titleMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun WatchControls(ui: WatchUi, fold: FoldState, onPause: () -> Unit, onKeepOn: () -> Unit, onFlip: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(NekoTokens.Spacing.l), verticalArrangement = Arrangement.spacedBy(NekoTokens.Spacing.s)) {
        Text(stringResource(R.string.watch_recent, ui.rows.size), style = MaterialTheme.typography.labelLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(NekoTokens.Spacing.s)) {
            FilledTonalButton(onClick = onPause, modifier = Modifier.weight(1f).heightIn(min = NekoTokens.Touch.tabletopTarget)) {
                Text(stringResource(if (ui.paused) R.string.watch_resume else R.string.watch_pause))
            }
            if (fold.posture == Posture.Tabletop) {
                FilledTonalButton(onClick = onKeepOn, modifier = Modifier.weight(1f).heightIn(min = NekoTokens.Touch.tabletopTarget)) {
                    Text(stringResource(if (ui.keepOn) R.string.watch_keep_on_stop else R.string.watch_keep_on))
                }
            }
            if (ui.rows.any { it.hasCode }) {
                Button(onClick = onFlip, modifier = Modifier.weight(1f).heightIn(min = NekoTokens.Touch.tabletopTarget)) {
                    Text(stringResource(R.string.watch_flip))
                }
            }
        }
    }
}

@Composable
internal fun WatchDesk(controller: DeviceInbox, state: InboxState, fold: FoldState, onExit: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val activity = context.activity()
    var visibleCode by remember { mutableStateOf<String?>(null) }
    var prompt by remember { mutableStateOf<CancellationSignal?>(null) }
    val flipTitle = stringResource(R.string.watch_flip_title)
    // Keeping the screen on never pauses the app lock (§10.4 ④ 常亮不阻止应用锁): the idle
    // timeout runs as usual, and once it fires the whole app, desk included, is locked.
    DisposableEffect(Unit) { onDispose { prompt?.cancel() } }
    LaunchedEffect(visibleCode) {
        if (visibleCode != null) {
            delay(30_000)
            visibleCode = null
        }
    }
    var keepOn by remember { mutableStateOf(false) }
    var paused by remember { mutableStateOf(false) }
    var visibleRows by remember { mutableStateOf(state.rows) }
    LaunchedEffect(state.rows, paused) { if (!paused) visibleRows = state.rows }
    val battery = remember { context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) }
    val status = battery?.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
    val level = battery?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
    val charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
    // Keep-screen-on only in tabletop, charging (or explicitly on) and above 20 %; it never counts as interaction.
    DisposableEffect(activity, keepOn, charging, fold.posture, level) {
        if (activity != null && fold.posture == Posture.Tabletop && level >= 20 && (charging || keepOn)) {
            activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        onDispose { activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }
    WatchDeskContent(
        ui = WatchUi(visibleRows, connected = state.error == null, code = visibleCode, paused = paused, keepOn = keepOn),
        fold = fold,
        onPause = { paused = !paused },
        onKeepOn = { keepOn = !keepOn },
        onFlip = {
            val recent = visibleRows.firstOrNull { it.hasCode }
            if (recent != null) {
                prompt?.cancel()
                prompt = DeviceAuth.unlock(context, flipTitle, null) { outcome ->
                    prompt = null
                    if (outcome is AuthOutcome.Success) scope.launch { visibleCode = controller.codeFor(recent.id) }
                }
            }
        },
        onExit = onExit,
    )
}
