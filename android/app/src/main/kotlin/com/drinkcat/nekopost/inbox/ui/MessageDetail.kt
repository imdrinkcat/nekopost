@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.drinkcat.nekopost.inbox.ui

import android.content.ClipData
import android.os.PersistableBundle
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.drinkcat.nekopost.R
import com.drinkcat.nekopost.core.ui.component.NekoIcons
import com.drinkcat.nekopost.core.ui.component.StatusPill
import com.drinkcat.nekopost.core.ui.fold.FoldState
import com.drinkcat.nekopost.core.ui.fold.Posture
import com.drinkcat.nekopost.core.ui.theme.NekoTextStyles
import com.drinkcat.nekopost.core.ui.theme.StatusTone
import com.drinkcat.nekopost.core.ui.token.NekoTokens
import com.drinkcat.nekopost.inbox.Delivery
import com.drinkcat.nekopost.inbox.InboxMessage
import com.drinkcat.nekopost.ui.common.SubScreenTopBar
import com.drinkcat.nekopost.ui.formatDate
import com.drinkcat.nekopost.ui.formatLongDateTime
import com.drinkcat.nekopost.ui.shell.PaneMode
import kotlinx.coroutines.delay

/**
 * One message, like a chat app's message view: back + sender in the top bar,
 * the verification code as a big card with one-tap copy, the selectable body,
 * and the delivery state at the bottom. Stateless.
 */
@Composable
fun MessageDetailPane(
    state: InboxUiState,
    mode: PaneMode,
    fold: FoldState,
    actions: InboxActions,
    modifier: Modifier = Modifier,
) {
    val message = state.detail?.takeIf { it.id == state.selectedId }
    if (message == null && state.selectedId != null) {
        // Opened from a notification (not in the loaded list yet), or the fetch failed.
        Column(modifier.fillMaxSize().testTag("inbox_detail_pending")) {
            SubScreenTopBar("", if (mode == PaneMode.Single) actions.onBack else null)
            Box(Modifier.weight(1f).fillMaxWidth().padding(NekoTokens.Spacing.xl), contentAlignment = Alignment.Center) {
                if (state.detailLoading) {
                    CircularProgressIndicator()
                } else {
                    Text(
                        state.error ?: stringResource(R.string.inbox_detail_unavailable),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
        return
    }
    if (message == null) {
        Column(
            modifier.fillMaxSize().padding(NekoTokens.Spacing.xl).testTag("inbox_detail_empty"),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.size(72.dp)) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(Icons.Filled.Email, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(32.dp))
                }
            }
            Spacer(Modifier.size(NekoTokens.Spacing.m))
            Text(stringResource(R.string.inbox_select_message), style = MaterialTheme.typography.titleMedium)
        }
        return
    }
    // Book posture: swipe on the right page edge for the next / previous message (§10.4 ⑮).
    val paging = if (fold.posture == Posture.Book) {
        Modifier.pointerInput(message.id) {
            var edge = false
            var drag = 0f
            detectHorizontalDragGestures(
                onDragStart = { where -> edge = where.x >= size.width - 48.dp.toPx(); drag = 0f },
                onHorizontalDrag = { change, amount -> if (edge) { change.consume(); drag += amount } },
                onDragEnd = { if (edge && kotlin.math.abs(drag) > 72.dp.toPx()) actions.onPage(if (drag < 0f) 1 else -1) },
            )
        }
    } else {
        Modifier
    }
    Column(modifier.fillMaxSize().then(paging).testTag("inbox_detail_${message.id}")) {
        DetailTopBar(message, mode, state.tools, actions)
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = NekoTokens.Spacing.l)
                .padding(bottom = NekoTokens.Spacing.xl),
            verticalArrangement = Arrangement.spacedBy(NekoTokens.Spacing.l),
        ) {
            Header(message)
            val code = message.otp
            if (code != null) {
                OtpCard(message, code, cover = fold.posture == Posture.Cover, copied = state.copiedId == message.id) {
                    actions.onCopyCode(message.id)
                }
            }
            Body(message, state.detailLoading)
            Footer(message)
        }
    }
}

@Composable
private fun DetailTopBar(message: InboxMessage, mode: PaneMode, tools: Boolean, actions: InboxActions) {
    var menu by rememberSaveable(message.id) { mutableStateOf(false) }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .padding(start = if (mode == PaneMode.Single) NekoTokens.Spacing.xs else NekoTokens.Spacing.l, end = NekoTokens.Spacing.xs),
    ) {
        if (mode == PaneMode.Single) {
            IconButton(onClick = actions.onBack, modifier = Modifier.testTag("inbox_back")) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
            }
            Spacer(Modifier.width(NekoTokens.Spacing.xs))
        }
        Text(
            message.displaySender,
            style = MaterialTheme.typography.titleLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).semantics { heading() },
        )
        Box {
            IconButton(onClick = { menu = true }, modifier = Modifier.testTag("detail_overflow")) {
                Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.inbox_more))
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(
                    text = { Text(stringResource(if (message.read) R.string.inbox_mark_unread else R.string.inbox_mark_read)) },
                    onClick = { menu = false; actions.onSetRead(message.id, !message.read) },
                )
                message.body?.let { body ->
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.inbox_copy_body)) },
                        onClick = { menu = false; actions.onCopyBody(body) },
                    )
                }
                if (mode == PaneMode.TwoPane && tools) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.inbox_open_adjacent)) },
                        onClick = { menu = false; actions.onOpenAdjacent(message.id) },
                    )
                }
            }
        }
    }
}

@Composable
private fun Header(message: InboxMessage) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        SourceAvatar(message, size = 44.dp)
        Spacer(Modifier.width(NekoTokens.Spacing.m))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(NekoTokens.Spacing.xxs)) {
            val origin = listOf(message.sourceName.ifBlank { message.sourceId }, message.category).filter(String::isNotBlank)
            if (origin.isNotEmpty()) {
                Text(origin.joinToString(" · "), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            message.receivedAt?.let {
                Text(formatLongDateTime(it), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (message.late) StatusPill(stringResource(R.string.inbox_late), StatusTone.Warning)
    }
}

/**
 * The verification code, big and monospaced, with one-tap copy (60 s sensitive
 * clipboard). On the cover display it is the ② card: tap anywhere to copy, it
 * flips to 「已复制 ✓」, long-press picks the code up to drag it into another
 * app (⑧), and it is pinned for five minutes.
 */
@Composable
fun OtpCard(message: InboxMessage, code: String, cover: Boolean, copied: Boolean, onCopy: () -> Unit) {
    val view = LocalView.current
    var expired by remember(message.id) { mutableStateOf(false) }
    if (cover) {
        LaunchedEffect(message.id) {
            delay(300_000)
            expired = true
        }
    }
    if (cover && expired) return
    val dragLabel = stringResource(R.string.inbox_code_clip_label)
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        modifier = Modifier
            .fillMaxWidth()
            .testTag("otp_card")
            .then(
                if (cover) {
                    Modifier.combinedClickable(
                        onClick = {
                            onCopy()
                            view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                        },
                        onLongClick = { startCodeDrag(view, code, dragLabel) },
                    )
                } else {
                    Modifier
                },
            ),
    ) {
        Column(
            Modifier.padding(NekoTokens.Spacing.l),
            verticalArrangement = Arrangement.spacedBy(NekoTokens.Spacing.s),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(stringResource(R.string.inbox_code_label), style = MaterialTheme.typography.labelLarge)
            if (copied) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.CheckCircle, contentDescription = null)
                    Spacer(Modifier.width(NekoTokens.Spacing.s))
                    Text(stringResource(R.string.inbox_code_copied), style = MaterialTheme.typography.titleLarge)
                }
            } else {
                Text(
                    code,
                    style = NekoTextStyles.mono.copy(fontSize = 40.sp, letterSpacing = 6.sp),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.testTag("otp_code"),
                )
            }
            if (cover) {
                Text(stringResource(R.string.inbox_code_tap_hint), style = MaterialTheme.typography.bodySmall)
            } else {
                FilledTonalButton(
                    onClick = onCopy,
                    modifier = Modifier.heightIn(min = NekoTokens.Touch.minTarget).testTag("otp_copy"),
                ) {
                    Icon(NekoIcons.Copy, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(NekoTokens.Spacing.s))
                    Text(stringResource(R.string.inbox_copy_code))
                }
                Text(stringResource(R.string.inbox_code_clear_hint), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

/** Only the digits leave the app, and only by an explicit drag (design §10.4 ⑧). */
private fun startCodeDrag(view: View, code: String, label: String) {
    if (!Regex("[0-9]{4,8}").matches(code)) return
    val clip = ClipData.newPlainText(label, code)
    clip.description.extras = PersistableBundle().apply { putBoolean("android.content.extra.IS_SENSITIVE", true) }
    view.startDragAndDrop(clip, View.DragShadowBuilder(view), null, View.DRAG_FLAG_GLOBAL)
}

@Composable
private fun Body(message: InboxMessage, loading: Boolean) {
    val body = message.body
    when {
        body != null -> SelectionContainer {
            Text(body, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.fillMaxWidth().testTag("detail_body"))
        }
        loading -> Box(Modifier.fillMaxWidth().padding(NekoTokens.Spacing.l), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 3.dp)
        }
        message.expired -> Text(
            stringResource(R.string.inbox_body_expired_detail),
            style = MaterialTheme.typography.bodyLarge,
            fontStyle = FontStyle.Italic,
            color = MaterialTheme.colorScheme.outline,
        )
        else -> Text(message.preview, style = MaterialTheme.typography.bodyLarge)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Footer(message: InboxMessage) {
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    val chips = deliveryChips(message.deliveries)
    if (chips.isNotEmpty()) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(NekoTokens.Spacing.s), verticalArrangement = Arrangement.spacedBy(NekoTokens.Spacing.s)) {
            chips.forEach { (channel, tone) ->
                val name = stringResource(if (channel == Channel.Telegram) R.string.inbox_delivery_telegram else R.string.inbox_delivery_app)
                val text = when (tone) {
                    StatusTone.Success -> stringResource(R.string.inbox_delivery_done, name)
                    StatusTone.Error -> stringResource(R.string.inbox_delivery_failed, name)
                    else -> stringResource(R.string.inbox_delivery_pending, name)
                }
                StatusPill(text, tone)
            }
        }
    }
    message.expiresAt?.let {
        Text(stringResource(R.string.inbox_body_expires, formatDate(it)), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private enum class Channel { Telegram, App }

private fun deliveryChips(deliveries: List<Delivery>): List<Pair<Channel, StatusTone>> {
    fun tone(group: List<Delivery>): StatusTone = when {
        group.any { it.delivered } -> StatusTone.Success
        group.all { it.state == "failed" || it.state == "uncertain" } -> StatusTone.Error
        else -> StatusTone.Pending
    }
    val shown = deliveries.filter { it.state != "skipped" }
    val out = mutableListOf<Pair<Channel, StatusTone>>()
    shown.filter { it.channel == "telegram" }.takeIf { it.isNotEmpty() }?.let { out += Channel.Telegram to tone(it) }
    shown.filter { it.channel == "fcm" }.takeIf { it.isNotEmpty() }?.let { out += Channel.App to tone(it) }
    return out
}
