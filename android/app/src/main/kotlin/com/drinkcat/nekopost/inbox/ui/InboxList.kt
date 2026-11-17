@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.drinkcat.nekopost.inbox.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import com.drinkcat.nekopost.ui.common.NoticeBar
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.AssistChip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.drinkcat.nekopost.lock.LocalAppLock
import com.drinkcat.nekopost.R
import com.drinkcat.nekopost.core.ui.component.EmptyState
import com.drinkcat.nekopost.core.ui.component.NekoIcons
import com.drinkcat.nekopost.core.ui.theme.StatusTone
import com.drinkcat.nekopost.core.ui.token.NekoTokens
import com.drinkcat.nekopost.inbox.InboxMessage
import com.drinkcat.nekopost.inbox.MessageKind
import com.drinkcat.nekopost.inbox.notify.NotificationFormatter
import com.drinkcat.nekopost.ui.LocalNow
import com.drinkcat.nekopost.ui.common.NoticeCard
import com.drinkcat.nekopost.ui.formatListTime
import com.drinkcat.nekopost.ui.shell.PaneMode
import java.time.Duration

/**
 * The conversation list (requirement: 像平时收消息的软件): title bar with the
 * overflow tools, a search field, filter chips, then one row per message with
 * the sender, a one-line preview, the time and an unread dot. Pull to refresh;
 * more rows load while scrolling.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InboxListPane(
    state: InboxUiState,
    mode: PaneMode,
    actions: InboxActions,
    listState: LazyListState,
    searchFocus: FocusRequester,
    onSearchFocused: (Boolean) -> Unit,
    menuOpen: Boolean,
    onMenuOpen: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val rows = state.visibleRows
    Column(modifier.fillMaxSize().testTag("inbox_list")) {
        if (mode == PaneMode.TopBottom) {
            // Tabletop: only the lower half is left for the list; title and search share one row.
            InboxTopBar(state, menuOpen, onMenuOpen, actions) {
                SearchField(state, actions, searchFocus, onSearchFocused, Modifier.weight(1f))
            }
        } else {
            InboxTopBar(state, menuOpen, onMenuOpen, actions)
            SearchField(state, actions, searchFocus, onSearchFocused)
        }
        FilterRow(state, mode, actions)
        Banners(state, actions)
        PullToRefreshBox(
            isRefreshing = state.refreshing,
            onRefresh = actions.onRefresh,
            modifier = Modifier.weight(1f).fillMaxWidth(),
        ) {
            when {
                state.revoked -> RevokedState(actions)
                state.loading && rows.isEmpty() -> LoadingState()
                rows.isEmpty() -> EmptyList(state, actions)
                else -> LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = NekoTokens.Spacing.s, end = NekoTokens.Spacing.s, bottom = NekoTokens.Spacing.xl),
                ) {
                    state.envelope?.let { letter ->
                        item(key = "envelope") { EnvelopeCard(letter, actions) }
                    }
                    if (state.filteringLocally) {
                        item(key = "local") {
                            Text(
                                stringResource(R.string.inbox_search_local),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = NekoTokens.Spacing.m, vertical = NekoTokens.Spacing.xs),
                            )
                        }
                    }
                    items(rows, key = { it.id }) { message ->
                        MessageRow(
                            message = message,
                            selected = mode != PaneMode.Single && message.id == state.selectedId,
                            onOpen = { actions.onOpen(message.id) },
                            onCopyCode = { actions.onCopyCode(message.id) },
                            onToggleRead = { actions.onSetRead(message.id, !message.read) },
                        )
                    }
                    if (state.loadingMore) {
                        item(key = "more") {
                            Box(Modifier.fillMaxWidth().padding(NekoTokens.Spacing.l), contentAlignment = Alignment.Center) {
                                CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                            }
                        }
                    }
                }
            }
        }
        state.toast?.let { Toast(it) }
    }
    val nearEnd by remember(listState, rows.size) {
        derivedStateOf {
            val last = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            rows.isNotEmpty() && last >= listState.layoutInfo.totalItemsCount - 4
        }
    }
    LaunchedEffect(nearEnd, state.hasMore) {
        if (nearEnd && state.hasMore && !state.loadingMore && state.searchedQuery == null) actions.onLoadMore()
    }
}

@Composable
private fun InboxTopBar(
    state: InboxUiState,
    menuOpen: Boolean,
    onMenuOpen: (Boolean) -> Unit,
    actions: InboxActions,
    search: (@Composable RowScope.() -> Unit)? = null,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .padding(start = NekoTokens.Spacing.l, end = NekoTokens.Spacing.xs),
    ) {
        if (search != null) {
            search()
        } else {
            Text(
                stringResource(R.string.inbox_title),
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.weight(1f).semantics { heading() },
            )
        }
        if (state.tools) Box {
            IconButton(onClick = { onMenuOpen(true) }, modifier = Modifier.testTag("inbox_overflow")) {
                Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.inbox_more))
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { onMenuOpen(false) }) {
                if (state.canSend) {
                    MenuItem(stringResource(R.string.inbox_tool_drop_note), Icons.Filled.Edit) {
                        onMenuOpen(false)
                        actions.onOpenTool(InboxSubScreen.DropNote)
                    }
                }
                if (state.canAdmin) {
                    MenuItem(stringResource(R.string.inbox_tool_sorting), NekoIcons.PostBox) {
                        onMenuOpen(false)
                        actions.onOpenTool(InboxSubScreen.Sorting)
                    }
                }
                MenuItem(stringResource(R.string.inbox_tool_watch), NekoIcons.CoverScreen) {
                    onMenuOpen(false)
                    actions.onOpenTool(InboxSubScreen.Watch)
                }
                if (state.hardwareKeyboard) {
                    MenuItem(stringResource(R.string.inbox_tool_shortcuts), Icons.Filled.Build) {
                        onMenuOpen(false)
                        actions.onOpenTool(InboxSubScreen.Shortcuts)
                    }
                }
            }
        }
    }
}

@Composable
private fun MenuItem(text: String, icon: ImageVector, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(text) },
        leadingIcon = { Icon(icon, contentDescription = null) },
        onClick = onClick,
    )
}

@Composable
private fun SearchField(
    state: InboxUiState,
    actions: InboxActions,
    focus: FocusRequester,
    onFocused: (Boolean) -> Unit,
    modifier: Modifier = Modifier.fillMaxWidth().padding(horizontal = NekoTokens.Spacing.l, vertical = NekoTokens.Spacing.xs),
) {
    TextField(
        value = state.query,
        onValueChange = { actions.onQueryChange(it.take(200)) },
        modifier = modifier
            .focusRequester(focus)
            .onFocusChanged { onFocused(it.isFocused) }
            .testTag("inbox_search"),
        placeholder = { Text(stringResource(R.string.inbox_search_hint)) },
        leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
        trailingIcon = if (state.query.isNotEmpty()) {
            {
                IconButton(onClick = actions.onClearSearch) {
                    Icon(Icons.Filled.Clear, contentDescription = stringResource(R.string.inbox_search_clear))
                }
            }
        } else {
            null
        },
        singleLine = true,
        shape = CircleShape,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { actions.onSearch() }),
        colors = TextFieldDefaults.colors(
            focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent,
        ),
    )
}

@Composable
private fun FilterRow(state: InboxUiState, mode: PaneMode, actions: InboxActions) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(NekoTokens.Spacing.s),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = NekoTokens.Spacing.l),
    ) {
        InboxFilter.entries.forEach { filter ->
            FilterChip(
                selected = state.filter == filter,
                onClick = { actions.onFilter(filter) },
                label = {
                    Text(
                        when (filter) {
                            InboxFilter.All -> stringResource(R.string.inbox_filter_all)
                            InboxFilter.Unread -> if (state.unreadCount > 0) {
                                stringResource(R.string.inbox_filter_unread_n, state.unreadCount)
                            } else {
                                stringResource(R.string.inbox_filter_unread)
                            }
                            InboxFilter.Codes -> stringResource(R.string.inbox_filter_codes)
                        },
                    )
                },
                modifier = Modifier.testTag("inbox_filter_${filter.name}"),
            )
        }
        // Tabletop: the fold tools sit on the lower half, next to the filters.
        if (mode == PaneMode.TopBottom) {
            // Tools, not filters: assist chips with an icon, after a divider.
            VerticalDivider(Modifier.height(24.dp).padding(horizontal = NekoTokens.Spacing.xs))
            ToolChip(stringResource(R.string.inbox_tool_watch), NekoIcons.CoverScreen) { actions.onOpenTool(InboxSubScreen.Watch) }
            if (state.canAdmin) {
                ToolChip(stringResource(R.string.inbox_tool_sorting), NekoIcons.PostBox) { actions.onOpenTool(InboxSubScreen.Sorting) }
            }
        }
    }
}

@Composable
private fun ToolChip(label: String, icon: ImageVector, onClick: () -> Unit) {
    AssistChip(
        onClick = onClick,
        label = { Text(label) },
        leadingIcon = { Icon(icon, contentDescription = null, modifier = Modifier.size(AssistChipDefaults.IconSize)) },
    )
}

@Composable
private fun Banners(state: InboxUiState, actions: InboxActions) {
    val offline = state.offlineSince
    if (offline != null && state.rows.isNotEmpty()) {
        val hours = Duration.between(offline, LocalNow.current()).toHours().coerceAtLeast(0)
        NoticeBar(
            text = if (hours < 1) {
                stringResource(R.string.inbox_offline_recent)
            } else {
                stringResource(R.string.inbox_offline_hours, hours.toInt())
            },
            tone = StatusTone.Warning,
            action = stringResource(R.string.action_retry),
            onAction = actions.onRefresh,
            modifier = Modifier.padding(horizontal = NekoTokens.Spacing.l, vertical = NekoTokens.Spacing.xs),
        )
    } else if (state.error != null && state.rows.isNotEmpty() && !state.revoked) {
        NoticeBar(
            text = state.error,
            tone = StatusTone.Error,
            action = stringResource(R.string.action_retry),
            onAction = actions.onRefresh,
            modifier = Modifier.padding(horizontal = NekoTokens.Spacing.l, vertical = NekoTokens.Spacing.xs),
        )
    }
}

/** First load: a few still placeholder rows (no spinner), so the list does not jump when it arrives. */
@Composable
private fun LoadingState() {
    val bar = MaterialTheme.colorScheme.surfaceContainerHigh
    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = NekoTokens.Spacing.s)
            .testTag("inbox_loading"),
    ) {
        Text(
            stringResource(R.string.inbox_loading),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = NekoTokens.Spacing.m, vertical = NekoTokens.Spacing.s),
        )
        repeat(6) { i ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().heightIn(min = 72.dp).padding(horizontal = NekoTokens.Spacing.m),
            ) {
                Box(Modifier.size(48.dp).background(bar, CircleShape))
                Spacer(Modifier.width(NekoTokens.Spacing.m))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(NekoTokens.Spacing.s)) {
                    Box(Modifier.fillMaxWidth(if (i % 2 == 0) 0.45f else 0.6f).height(14.dp).background(bar, MaterialTheme.shapes.extraSmall))
                    Box(Modifier.fillMaxWidth(if (i % 3 == 0) 0.9f else 0.75f).height(12.dp).background(bar, MaterialTheme.shapes.extraSmall))
                }
            }
        }
    }
}

@Composable
private fun EmptyList(state: InboxUiState, actions: InboxActions) {
    Column(
        Modifier.fillMaxSize().padding(top = NekoTokens.Spacing.xl).testTag("inbox_empty"),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        val searched = state.searchedQuery ?: state.query.takeIf { it.isNotBlank() }
        when {
            state.error != null -> {
                NoticeCard(
                    text = state.error,
                    tone = StatusTone.Error,
                    action = stringResource(R.string.action_retry),
                    onAction = actions.onRefresh,
                    modifier = Modifier.padding(NekoTokens.Spacing.l),
                )
            }
            searched != null -> {
                EmptyState(
                    title = stringResource(R.string.inbox_search_none, searched),
                    message = stringResource(
                        if (state.searchedQuery == null) R.string.inbox_search_none_local else R.string.inbox_search_none_detail,
                    ),
                    showCat = false,
                )
                TextButton(onClick = actions.onClearSearch) { Text(stringResource(R.string.inbox_search_clear)) }
            }
            state.filter == InboxFilter.Unread -> EmptyState(
                title = stringResource(R.string.inbox_empty_unread),
                message = stringResource(R.string.inbox_empty_unread_detail),
            )
            state.filter == InboxFilter.Codes -> EmptyState(
                title = stringResource(R.string.inbox_empty_codes),
                message = stringResource(R.string.inbox_empty_codes_detail),
                showCat = false,
            )
            else -> {
                EmptyState(
                    title = stringResource(R.string.inbox_empty_title),
                    message = stringResource(R.string.inbox_empty_detail),
                )
                if (state.notificationsBlocked) {
                    Button(
                        onClick = actions.onEnableNotifications,
                        modifier = Modifier.heightIn(min = NekoTokens.Touch.minTarget).testTag("inbox_enable_notifications"),
                    ) {
                        Icon(Icons.Filled.Notifications, contentDescription = null)
                        Spacer(Modifier.width(NekoTokens.Spacing.s))
                        Text(stringResource(R.string.inbox_enable_notifications))
                    }
                }
            }
        }
    }
}

@Composable
private fun RevokedState(actions: InboxActions) {
    Column(
        Modifier.fillMaxSize().padding(NekoTokens.Spacing.l),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        NoticeCard(
            title = stringResource(R.string.inbox_revoked_title),
            text = stringResource(R.string.inbox_revoked_detail),
            tone = StatusTone.Error,
            action = stringResource(R.string.inbox_pair_again),
            onAction = actions.onRepair,
        )
    }
}

@Composable
private fun Toast(text: String) {
    Box(Modifier.fillMaxWidth().padding(NekoTokens.Spacing.m), contentAlignment = Alignment.Center) {
        Surface(
            shape = MaterialTheme.shapes.small,
            color = MaterialTheme.colorScheme.inverseSurface,
            contentColor = MaterialTheme.colorScheme.inverseOnSurface,
        ) {
            Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(horizontal = NekoTokens.Spacing.l, vertical = NekoTokens.Spacing.m))
        }
    }
}

/** One conversation row: avatar, sender + time, one-line preview, code chip and unread dot. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MessageRow(
    message: InboxMessage,
    selected: Boolean,
    onOpen: () -> Unit,
    onCopyCode: () -> Unit,
    onToggleRead: () -> Unit,
) {
    var menu by rememberSaveable(message.id, LocalAppLock.current.epoch) { mutableStateOf(false) }
    val unread = !message.read
    val readLabel = stringResource(if (unread) R.string.inbox_mark_read else R.string.inbox_mark_unread)
    val copyLabel = stringResource(R.string.inbox_copy_code)
    Box {
        Surface(
            shape = MaterialTheme.shapes.medium,
            color = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface,
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 1.dp)
                .testTag("inbox_row_${message.id}"),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .combinedClickable(onClick = onOpen, onLongClick = { menu = true })
                    .semantics {
                        customActions = buildList {
                            add(CustomAccessibilityAction(readLabel) { onToggleRead(); true })
                            if (message.hasCode && !message.expired) add(CustomAccessibilityAction(copyLabel) { onCopyCode(); true })
                        }
                    }
                    .heightIn(min = 72.dp)
                    .padding(horizontal = NekoTokens.Spacing.m, vertical = NekoTokens.Spacing.s),
            ) {
                SourceAvatar(message, size = 48.dp)
                Spacer(Modifier.width(NekoTokens.Spacing.m))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(NekoTokens.Spacing.xxs)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            message.senderLabel(),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = if (unread) FontWeight.Bold else FontWeight.Normal,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        message.receivedAt?.let {
                            Spacer(Modifier.width(NekoTokens.Spacing.s))
                            Text(
                                formatListTime(it),
                                style = MaterialTheme.typography.labelMedium,
                                color = if (unread) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    // Fixed height: a row with a code is exactly as tall as any other row.
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.height(24.dp)) {
                        PreviewText(message, Modifier.weight(1f))
                        if (message.hasCode && !message.expired) {
                            Spacer(Modifier.width(NekoTokens.Spacing.s))
                            CodeChip(copyLabel, onCopyCode)
                        }
                        if (unread) {
                            Spacer(Modifier.width(NekoTokens.Spacing.s))
                            Box(
                                Modifier
                                    .size(10.dp)
                                    .background(MaterialTheme.colorScheme.primary, CircleShape)
                                    .testTag("unread_dot"),
                            )
                        }
                    }
                }
            }
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(text = { Text(readLabel) }, onClick = { menu = false; onToggleRead() })
            if (message.hasCode && !message.expired) {
                DropdownMenuItem(text = { Text(copyLabel) }, onClick = { menu = false; onCopyCode() })
            }
        }
    }
}

@Composable
private fun PreviewText(message: InboxMessage, modifier: Modifier) {
    if (message.expired) {
        Text(
            stringResource(R.string.inbox_body_expired),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.outline,
            maxLines = 1,
            modifier = modifier,
        )
    } else {
        val text = maskedPreview(message.preview.lineSequence().map(String::trim).firstOrNull(String::isNotBlank))
            ?: message.category.ifBlank { message.sourceName }
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = modifier,
        )
    }
}

/**
 * The server masks codes as runs of 「*」; in a list that reads like a broken
 * row, so the run becomes a neutral 「••••••」.
 */
internal fun maskedPreview(preview: String?): String? = preview?.replace(Regex("\\*{3,}"), "••••••")

/**
 * The name on the row. Once the body has expired the server no longer sends a
 * sender; the row is then named by category and kind (「快递短信」), not by the
 * forwarding phone, which would read as a message from the user's own phone.
 */
@Composable
fun InboxMessage.senderLabel(): String {
    displaySender.takeIf(String::isNotBlank)?.let { return it }
    val kindWord = stringResource(
        when (kind) {
            MessageKind.Sms -> R.string.inbox_kind_sms
            MessageKind.Notification -> R.string.inbox_kind_notification
            MessageKind.Note -> R.string.inbox_kind_note
            MessageKind.Script -> R.string.inbox_kind_script
        },
    )
    return if (category.isNotBlank()) stringResource(R.string.inbox_sender_category_kind, category, kindWord) else stringResource(R.string.inbox_sender_expired)
}

/**
 * Solid primary (§9.3: the code is the most prominent element of the app), in
 * the preview line's fixed 24dp so a row with a code is as tall as any other.
 * The pill itself is a small target; the row's long-press menu and its
 * accessibility action copy the code too.
 */
@Composable
private fun CodeChip(label: String, onCopy: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(NekoTokens.Spacing.xs),
        modifier = Modifier
            .height(24.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.primary)
            .clickable(onClickLabel = label, role = Role.Button, onClick = onCopy)
            .padding(horizontal = NekoTokens.Spacing.s)
            .testTag("code_chip"),
    ) {
        Icon(NekoIcons.Copy, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(14.dp))
        Text(stringResource(R.string.inbox_code_chip), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onPrimary)
    }
}

/**
 * The source's emoji icon, or the first letter on a tonal circle whose colour
 * is stable per source; a small badge tells SMS, notification, note and script apart.
 */
@Composable
fun SourceAvatar(message: InboxMessage, size: Dp) {
    val dark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    val palette = if (dark) NekoTokens.Avatar.dark else NekoTokens.Avatar.light
    // One colour per conversation (sender), like a messaging app's contact avatars.
    val name = message.senderLabel()
    val key = message.displaySender.ifBlank { message.sourceId }
    val (container, content) = palette[NekoTokens.Avatar.index(key)]
    val label = NotificationFormatter.avatarLabel(message.sourceIcon, name)
    Box(Modifier.size(size)) {
        Surface(shape = CircleShape, color = container, contentColor = content, modifier = Modifier.size(size)) {
            Box(contentAlignment = Alignment.Center) {
                Text(label, style = if (size >= 48.dp) MaterialTheme.typography.titleLarge else MaterialTheme.typography.titleMedium)
            }
        }
        val badge = when (message.kind) {
            MessageKind.Sms -> NekoIcons.Sms
            MessageKind.Notification -> Icons.Filled.Notifications
            MessageKind.Note -> Icons.Filled.Edit
            MessageKind.Script -> NekoIcons.Script
        }
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surfaceContainerHighest,
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .size(size * 0.42f)
                .align(Alignment.BottomEnd)
                .offset(x = 2.dp, y = 2.dp)
                .border(2.dp, MaterialTheme.colorScheme.surface, CircleShape),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(badge, contentDescription = null, modifier = Modifier.size(size * 0.26f))
            }
        }
    }
}

/** Cover → inner unfold with an unread letter waiting (§10.4 ⑫): one tap opens it. */
@Composable
private fun EnvelopeCard(message: InboxMessage, actions: InboxActions) {
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.tertiaryContainer,
        contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
        modifier = Modifier.fillMaxWidth().padding(NekoTokens.Spacing.s).testTag("inbox_envelope"),
    ) {
        Row(Modifier.padding(NekoTokens.Spacing.m), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.inbox_envelope_title), style = MaterialTheme.typography.labelLarge)
                Text(message.senderLabel(), style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            TextButton(onClick = { actions.onOpen(message.id) }) { Text(stringResource(R.string.inbox_envelope_open)) }
            TextButton(onClick = actions.onDismissEnvelope) { Text(stringResource(R.string.action_close)) }
        }
    }
}
