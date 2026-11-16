package com.drinkcat.nekopost.ui.log

import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.drinkcat.nekopost.R
import com.drinkcat.nekopost.core.forwarder.ForwardKind
import com.drinkcat.nekopost.core.forwarder.ForwardLogItem
import com.drinkcat.nekopost.core.ui.component.EmptyState
import com.drinkcat.nekopost.core.ui.component.ForwardStatusPill
import com.drinkcat.nekopost.core.ui.component.KeyValueRow
import com.drinkcat.nekopost.core.ui.component.NekoCard
import com.drinkcat.nekopost.core.ui.fold.FoldState
import com.drinkcat.nekopost.core.ui.fold.HingeAwareSplit
import com.drinkcat.nekopost.core.ui.fold.SplitAxis
import com.drinkcat.nekopost.core.ui.token.NekoTokens
import com.drinkcat.nekopost.ui.formatDateTime
import com.drinkcat.nekopost.ui.kindText
import com.drinkcat.nekopost.ui.shell.PaneMode
import com.drinkcat.nekopost.ui.shell.ShellUiState

/**
 * 记录: the latest forwarded items (metadata only).
 *  - Single: list.
 *  - TwoPane: list + detail of the selected item.
 *  - TopBottom: detail above the hinge, list below.
 */
@Composable
fun LogScreen(state: ShellUiState, fold: FoldState, mode: PaneMode) {
    val log = state.log
    if (log.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            EmptyState(
                title = stringResource(R.string.log_empty_title),
                message = stringResource(R.string.log_empty_body),
            )
        }
        return
    }

    // Only the ID is saved, never content (design §10.6).
    var selectedId by rememberSaveable { mutableStateOf<Long?>(null) }
    val selected = log.firstOrNull { it.id == selectedId } ?: log.first().takeIf { mode != PaneMode.Single }
    val onSelect: (ForwardLogItem) -> Unit = { selectedId = it.id }

    when (mode) {
        PaneMode.Single -> LogList(log, selectedId = null, onSelect = onSelect, header = true)
        PaneMode.TwoPane -> HingeAwareSplit(
            fold = fold,
            axis = SplitAxis.Horizontal,
            fraction = 0.42f,
            gap = NekoTokens.Spacing.s,
            modifier = Modifier.fillMaxSize(),
            first = { LogList(log, selectedId = selected?.id, onSelect = onSelect, header = true) },
            second = { LogDetailPane(selected) },
        )
        PaneMode.TopBottom -> HingeAwareSplit(
            fold = fold,
            axis = SplitAxis.Vertical,
            modifier = Modifier.fillMaxSize(),
            first = { LogDetailPane(selected) },
            second = { LogList(log, selectedId = selected?.id, onSelect = onSelect, header = false) },
        )
    }
}

@Composable
private fun LogList(
    log: List<ForwardLogItem>,
    selectedId: Long?,
    onSelect: (ForwardLogItem) -> Unit,
    header: Boolean,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(NekoTokens.Spacing.l),
        verticalArrangement = Arrangement.spacedBy(NekoTokens.Spacing.s),
    ) {
        if (header) {
            item(key = "header") {
                Column(Modifier.padding(bottom = NekoTokens.Spacing.s)) {
                    Text(stringResource(R.string.log_title), style = MaterialTheme.typography.headlineSmall)
                    Text(
                        stringResource(R.string.log_subtitle),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        items(log, key = { it.id }) { item ->
            LogRow(item, selected = item.id == selectedId, onClick = { onSelect(item) })
        }
    }
}

@Composable
private fun LogRow(item: ForwardLogItem, selected: Boolean, onClick: () -> Unit) {
    Surface(
        shape = MaterialTheme.shapes.small,
        color = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
    ) {
        Row(
            modifier = Modifier
                .heightIn(min = 64.dp)
                .padding(horizontal = NekoTokens.Spacing.m, vertical = NekoTokens.Spacing.s),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            KindBadge(item.kind)
            Spacer(Modifier.width(NekoTokens.Spacing.m))
            Column(Modifier.weight(1f)) {
                Text(
                    item.title,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    "${kindText(item.kind)} · ${formatDateTime(item.createdAt)}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(NekoTokens.Spacing.s))
            ForwardStatusPill(item.status)
        }
    }
}

@Composable
private fun KindBadge(kind: ForwardKind) {
    Surface(shape = CircleShape, color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.size(40.dp)) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = when (kind) {
                    ForwardKind.Sms -> Icons.Filled.Email
                    ForwardKind.Notification -> Icons.Filled.Notifications
                    ForwardKind.Test -> Icons.Filled.Star
                },
                contentDescription = kindText(kind),
                tint = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

@Composable
private fun LogDetailPane(item: ForwardLogItem?) {
    if (item == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(stringResource(R.string.log_detail_empty), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(NekoTokens.Spacing.l),
        verticalArrangement = Arrangement.spacedBy(NekoTokens.Spacing.m),
    ) {
        Text(item.title, style = MaterialTheme.typography.headlineSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
        ForwardStatusPill(item.status, large = true)
        NekoCard {
            KeyValueRow(stringResource(R.string.log_kind), kindText(item.kind))
            KeyValueRow(stringResource(R.string.log_created), formatDateTime(item.createdAt))
            item.sourcePackage?.let { KeyValueRow(stringResource(R.string.log_package), it, mono = true) }
            KeyValueRow(stringResource(R.string.log_attempts), item.attempts.toString(), mono = true)
            item.lastHttpStatus?.let { KeyValueRow(stringResource(R.string.log_http), it.toString(), mono = true) }
            item.lastError?.let { KeyValueRow(stringResource(R.string.log_error), it, mono = true) }
            item.serverMessageId?.let { KeyValueRow(stringResource(R.string.log_server_id), it, mono = true) }
        }
    }
}
