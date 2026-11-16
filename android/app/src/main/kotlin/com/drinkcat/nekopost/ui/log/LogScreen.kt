package com.drinkcat.nekopost.ui.log

import androidx.annotation.StringRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.drinkcat.nekopost.R
import com.drinkcat.nekopost.core.forwarder.ForwardKind
import com.drinkcat.nekopost.core.forwarder.ForwardLogItem
import com.drinkcat.nekopost.core.forwarder.ForwardStatus
import com.drinkcat.nekopost.core.ui.component.EmptyState
import com.drinkcat.nekopost.core.ui.component.ForwardStatusPill
import com.drinkcat.nekopost.core.ui.component.KeyValueRow
import com.drinkcat.nekopost.core.ui.component.NekoCard
import com.drinkcat.nekopost.core.ui.component.NekoIcons
import com.drinkcat.nekopost.core.ui.component.StatusPill
import com.drinkcat.nekopost.core.ui.fold.FoldState
import com.drinkcat.nekopost.core.ui.fold.HingeAwareSplit
import com.drinkcat.nekopost.core.ui.fold.SplitAxis
import com.drinkcat.nekopost.core.ui.theme.NekopostTheme
import com.drinkcat.nekopost.core.ui.theme.StatusTone
import com.drinkcat.nekopost.core.ui.token.NekoTokens
import com.drinkcat.nekopost.ui.common.AdaptiveListDetail
import com.drinkcat.nekopost.ui.common.PaneHeader
import com.drinkcat.nekopost.ui.forward.ForwardingMonitor
import com.drinkcat.nekopost.ui.LocalDisplayZone
import com.drinkcat.nekopost.ui.formatDay
import com.drinkcat.nekopost.ui.formatFullDateTime
import com.drinkcat.nekopost.ui.formatRelative
import com.drinkcat.nekopost.ui.formatTime
import com.drinkcat.nekopost.ui.kindText
import com.drinkcat.nekopost.ui.shell.PaneMode
import com.drinkcat.nekopost.ui.shell.ShellMemory
import com.drinkcat.nekopost.ui.shell.ShellUiState

/** Status groups offered as filters and shown as row chips. */
enum class LogStatusFilter(@param:StringRes val label: Int, val tone: StatusTone) {
    All(R.string.filter_all, StatusTone.Neutral),
    Delivered(R.string.filter_delivered, StatusTone.Success),
    Pending(R.string.filter_pending, StatusTone.Pending),
    Failed(R.string.filter_failed, StatusTone.Error),
    Skipped(R.string.filter_skipped, StatusTone.Neutral),
    ;

    fun matches(status: ForwardStatus): Boolean = this == All || groupOf(status) == this

    companion object {
        fun groupOf(status: ForwardStatus): LogStatusFilter = when (status) {
            ForwardStatus.Accepted, ForwardStatus.Duplicate -> Delivered
            ForwardStatus.Queued, ForwardStatus.Sending, ForwardStatus.Retrying, ForwardStatus.Paused -> Pending
            ForwardStatus.Failed -> Failed
            ForwardStatus.Skipped -> Skipped
        }
    }
}

enum class LogKindFilter(@param:StringRes val label: Int) {
    All(R.string.filter_all_kinds),
    Sms(R.string.log_kind_sms),
    Notification(R.string.log_kind_notification),
    ;

    fun matches(kind: ForwardKind): Boolean = when (this) {
        All -> true
        Sms -> kind == ForwardKind.Sms
        Notification -> kind == ForwardKind.Notification
    }
}

/**
 * 记录: the latest forwarded items, metadata only.
 *  - Single (cover): list; tapping opens the detail, back returns.
 *  - TwoPane (inner display): ListDetailPaneScaffold, list + detail.
 *  - TopBottom (tabletop): the selected item (or the live monitor) above the
 *    hinge, filters and list below.
 * The selection and filters live in [ShellMemory], so they carry over between
 * postures (select on the inner display, fold, and the cover shows the same item).
 */
@Composable
fun LogScreen(
    state: ShellUiState,
    fold: FoldState,
    mode: PaneMode,
    memory: ShellMemory,
    onCopy: (String) -> Unit = {},
) {
    if (state.log.isEmpty()) {
        if (mode == PaneMode.TopBottom) {
            HingeAwareSplit(
                fold = fold,
                axis = SplitAxis.Vertical,
                modifier = Modifier.fillMaxSize(),
                first = { ForwardingMonitor(state) },
                second = { EmptyLog() },
            )
        } else {
            EmptyLog()
        }
        return
    }

    val filtered = state.log.filter { memory.statusFilter.matches(it.status) && memory.kindFilter.matches(it.kind) }
    val selected = state.log.firstOrNull { it.id == memory.selectedLogId }
    val onSelect: (ForwardLogItem) -> Unit = { memory.selectedLogId = it.id }

    when (mode) {
        PaneMode.Single, PaneMode.TwoPane -> {
            val twoPane = mode == PaneMode.TwoPane
            // On two panes something is always shown on the right: the selection or the newest row.
            val shown = selected ?: if (twoPane) filtered.firstOrNull() else null
            AdaptiveListDetail(
                fold = fold,
                twoPane = twoPane,
                showDetail = selected != null,
                onBack = { memory.selectedLogId = null },
                list = {
                    LogListPane(filtered, memory, selectedId = if (twoPane) shown?.id else null, onSelect = onSelect, header = true)
                },
                detail = {
                    if (shown != null) {
                        LogDetailPane(
                            item = shown,
                            onBack = if (twoPane) null else ({ memory.selectedLogId = null }),
                            onCopy = onCopy,
                        )
                    } else {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text(stringResource(R.string.log_detail_empty), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                },
                modifier = Modifier.testTag(if (twoPane) "log_two_pane" else "log_single"),
            )
        }

        PaneMode.TopBottom -> HingeAwareSplit(
            fold = fold,
            axis = SplitAxis.Vertical,
            modifier = Modifier.fillMaxSize().testTag("log_tabletop"),
            first = {
                if (selected != null) {
                    LogDetailPane(selected, onBack = null, onCopy = null, display = true)
                } else {
                    ForwardingMonitor(state)
                }
            },
            second = { LogListPane(filtered, memory, selectedId = selected?.id, onSelect = onSelect, header = false) },
        )
    }
}

@Composable
private fun EmptyLog() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        EmptyState(
            title = stringResource(R.string.log_empty_title),
            message = stringResource(R.string.log_empty_body),
        )
    }
}

@Composable
private fun LogListPane(
    items: List<ForwardLogItem>,
    memory: ShellMemory,
    selectedId: Long?,
    onSelect: (ForwardLogItem) -> Unit,
    header: Boolean,
) {
    val zone = LocalDisplayZone.current
    val dates = items.map { it.createdAt.atZone(zone).toLocalDate() }
    LazyColumn(
        state = memory.logListState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(NekoTokens.Spacing.l),
        verticalArrangement = Arrangement.spacedBy(NekoTokens.Spacing.s),
    ) {
        if (header) {
            item(key = "header") {
                PaneHeader(stringResource(R.string.log_title), stringResource(R.string.log_subtitle))
            }
        }
        item(key = "filters") { Filters(memory) }
        if (items.isEmpty()) {
            item(key = "none") {
                Text(
                    stringResource(R.string.log_filter_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = NekoTokens.Spacing.xl),
                )
            }
        }
        items.forEachIndexed { i, entry ->
            if (i == 0 || dates[i] != dates[i - 1]) {
                item(key = "day-${dates[i]}") { DayHeader(entry) }
            }
            item(key = entry.id) {
                LogRow(entry, selected = entry.id == selectedId, onClick = { onSelect(entry) })
            }
        }
    }
}

@Composable
private fun DayHeader(item: ForwardLogItem) {
    Text(
        formatDay(item.createdAt),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = NekoTokens.Spacing.s, start = NekoTokens.Spacing.xs).semantics { heading() },
    )
}

@Composable
private fun Filters(memory: ShellMemory) {
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(NekoTokens.Spacing.s),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LogStatusFilter.entries.forEach { f ->
            FilterChip(
                selected = memory.statusFilter == f,
                onClick = { memory.statusFilter = f },
                label = { Text(stringResource(f.label)) },
                modifier = Modifier.testTag("filter_${f.name}"),
            )
        }
        VerticalDivider(Modifier.heightIn(max = 24.dp).padding(horizontal = NekoTokens.Spacing.xs))
        LogKindFilter.entries.filter { it != LogKindFilter.All }.forEach { k ->
            FilterChip(
                selected = memory.kindFilter == k,
                onClick = { memory.kindFilter = if (memory.kindFilter == k) LogKindFilter.All else k },
                label = { Text(stringResource(k.label)) },
            )
        }
    }
}

@Composable
private fun LogRow(item: ForwardLogItem, selected: Boolean, onClick: () -> Unit) {
    val group = LogStatusFilter.groupOf(item.status)
    Surface(
        shape = MaterialTheme.shapes.small,
        color = if (selected) MaterialTheme.colorScheme.surfaceContainerHighest else MaterialTheme.colorScheme.surfaceContainerLow,
        border = if (selected) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null,
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = selected, role = Role.Button, onClick = onClick)
            .testTag("log_row_${item.id}"),
    ) {
        Row(
            modifier = Modifier
                .heightIn(min = 68.dp)
                .padding(horizontal = NekoTokens.Spacing.m, vertical = NekoTokens.Spacing.s),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            KindBadge(item.kind)
            Spacer(Modifier.width(NekoTokens.Spacing.m))
            Column(Modifier.weight(1f)) {
                Text(item.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    previewLine(item),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(NekoTokens.Spacing.s))
            StatusPill(text = stringResource(group.label), tone = group.tone)
        }
    }
}

/** "短信 · 10:17 · 第 2 次尝试": category, time and the one fact that matters. Never the body. */
@Composable
private fun previewLine(item: ForwardLogItem): String {
    val parts = mutableListOf(kindText(item.kind), formatTime(item.createdAt))
    when (item.status) {
        ForwardStatus.Retrying, ForwardStatus.Failed ->
            if (item.attempts > 0) parts += stringResource(R.string.log_attempt_n, item.attempts)
        ForwardStatus.Duplicate -> parts += stringResource(R.string.log_duplicate_short)
        else -> Unit
    }
    return parts.joinToString(" · ")
}

@Composable
fun KindBadge(kind: ForwardKind, size: Dp = 40.dp) {
    Surface(shape = CircleShape, color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.size(size)) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = when (kind) {
                    ForwardKind.Sms -> NekoIcons.Sms
                    ForwardKind.Notification -> Icons.Filled.Notifications
                    ForwardKind.Test -> NekoIcons.TestLetter
                },
                contentDescription = kindText(kind),
                tint = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.size(size * 0.5f),
            )
        }
    }
}

/**
 * Metadata of one item: what it was, when, what the server said. [display]
 * renders the read-only tabletop variant (bigger type, no buttons).
 */
@Composable
private fun LogDetailPane(item: ForwardLogItem, onBack: (() -> Unit)?, onCopy: ((String) -> Unit)?, display: Boolean = false) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(NekoTokens.Spacing.l)
            .testTag("log_detail_${item.id}"),
        verticalArrangement = Arrangement.spacedBy(NekoTokens.Spacing.m),
    ) {
        if (onBack != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                }
                Text(stringResource(R.string.log_detail_title), style = MaterialTheme.typography.titleMedium)
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            KindBadge(item.kind, size = if (display) 64.dp else 52.dp)
            Spacer(Modifier.width(NekoTokens.Spacing.l))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(NekoTokens.Spacing.xs)) {
                Text(
                    item.title,
                    style = if (display) MaterialTheme.typography.headlineMedium else MaterialTheme.typography.headlineSmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.semantics { heading() },
                )
                Text(
                    "${kindText(item.kind)} · ${formatRelative(item.createdAt)}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        ForwardStatusPill(item.status, large = true)
        if (!display) {
            NekoCard(title = stringResource(R.string.log_timeline)) { Timeline(item) }
        }
        NekoCard(title = stringResource(R.string.log_metadata)) {
            KeyValueRow(stringResource(R.string.log_kind), kindText(item.kind))
            item.sourcePackage?.let { KeyValueRow(stringResource(R.string.log_package), it, mono = true) }
            KeyValueRow(stringResource(R.string.log_created), formatFullDateTime(item.createdAt), mono = true)
            if (item.updatedAt != item.createdAt) {
                KeyValueRow(stringResource(R.string.log_updated), formatFullDateTime(item.updatedAt), mono = true)
            }
            KeyValueRow(stringResource(R.string.log_attempts), item.attempts.toString(), mono = true)
            item.lastHttpStatus?.let { KeyValueRow(stringResource(R.string.log_http), it.toString(), mono = true) }
            item.lastError?.let { KeyValueRow(stringResource(R.string.log_error), errorText(it)) }
            item.serverMessageId?.let { id ->
                KeyValueRow(stringResource(R.string.log_server_id), id, mono = true)
                if (onCopy != null) {
                    TextButton(onClick = { onCopy(id) }, modifier = Modifier.heightIn(min = NekoTokens.Touch.minTarget)) {
                        Text(stringResource(R.string.log_copy_id))
                    }
                }
            }
        }
        if (!display) {
            Text(
                stringResource(R.string.log_privacy_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * Explains a stored error code in Chinese. Codes are exception class names
 * (network failures), server error codes (docs/api.md), "http_NNN" or the
 * queue's own markers; the raw code stays in brackets for troubleshooting.
 */
@Composable
internal fun errorText(code: String): String {
    @StringRes val res = when {
        code == "UnknownHostException" -> R.string.err_unknown_host
        code == "SocketTimeoutException" || code == "InterruptedIOException" -> R.string.err_timeout
        code == "ConnectException" || code == "NoRouteToHostException" -> R.string.err_connect
        code.startsWith("SSL") || code == "certificate_pinning" -> R.string.err_tls
        code == "unauthorized" -> R.string.err_unauthorized
        code == "source_disabled" -> R.string.err_source_disabled
        code == "rate_limited" || code == "too_many_auth_failures" || code == "busy" -> R.string.err_rate_limited
        code == "payload_too_large" -> R.string.err_too_large
        code.startsWith("invalid_") || code == "validation_failed" || code == "unsupported_media_type" -> R.string.err_rejected
        code == "expired" -> R.string.err_expired
        code == "queue_full" -> R.string.err_queue_full
        code.startsWith("http_5") || code == "internal_error" -> R.string.err_server
        code.endsWith("Exception") -> R.string.err_network
        else -> return code
    }
    return stringResource(R.string.err_with_code, stringResource(res), code)
}

/** Queued → attempts → outcome, as a small vertical timeline. */
@Composable
private fun Timeline(item: ForwardLogItem) {
    val queued = stringResource(R.string.timeline_queued)
    val attempts = stringResource(R.string.timeline_attempts, item.attempts)
    val group = LogStatusFilter.groupOf(item.status)
    val outcome = stringResource(if (group == LogStatusFilter.Pending) R.string.timeline_waiting else item.status.outcomeLabel())
    val created = formatTime(item.createdAt)
    val updated = formatTime(item.updatedAt)
    val steps = buildList {
        add(TimelineStep(queued, created, StatusTone.Pending))
        if (item.attempts > 0) {
            add(
                TimelineStep(
                    attempts,
                    item.lastHttpStatus?.let { "HTTP $it" } ?: item.lastError?.let { errorText(it) }.orEmpty(),
                    if (item.status == ForwardStatus.Retrying) StatusTone.Warning else StatusTone.Pending,
                ),
            )
        }
        if (group != LogStatusFilter.Pending) {
            add(TimelineStep(outcome, updated, group.tone))
        } else {
            add(TimelineStep(outcome, "", StatusTone.Neutral))
        }
    }
    Column {
        steps.forEachIndexed { i, s ->
            Row(Modifier.fillMaxWidth().heightIn(min = 40.dp)) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(20.dp)) {
                    val color = if (s.tone == StatusTone.Neutral) MaterialTheme.colorScheme.outline else NekopostTheme.colors.tone(s.tone).content
                    Surface(shape = CircleShape, color = color, modifier = Modifier.padding(top = 4.dp).size(10.dp)) {}
                    if (i < steps.lastIndex) {
                        Surface(color = MaterialTheme.colorScheme.outlineVariant, modifier = Modifier.width(2.dp).heightIn(min = 26.dp)) {}
                    }
                }
                Spacer(Modifier.width(NekoTokens.Spacing.m))
                Text(s.label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                if (s.detail.isNotEmpty()) {
                    Text(s.detail, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

private data class TimelineStep(val label: String, val detail: String, val tone: StatusTone)

private fun ForwardStatus.outcomeLabel(): Int = when (this) {
    ForwardStatus.Accepted -> R.string.timeline_accepted
    ForwardStatus.Duplicate -> R.string.timeline_duplicate
    ForwardStatus.Failed -> R.string.timeline_failed
    ForwardStatus.Skipped -> R.string.timeline_skipped
    else -> R.string.timeline_waiting
}
