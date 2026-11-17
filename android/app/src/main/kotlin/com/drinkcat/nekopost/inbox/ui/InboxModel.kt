package com.drinkcat.nekopost.inbox.ui

import androidx.compose.runtime.Immutable
import com.drinkcat.nekopost.inbox.BatchRead
import com.drinkcat.nekopost.inbox.InboxMessage
import java.time.Instant

enum class InboxFilter { All, Unread, Codes }

/** Tools reached from the inbox overflow (moved out of the main flow, none removed). */
enum class InboxSubScreen { None, Pair, Sorting, Watch, DropNote, Shortcuts }

/** Everything the stateless inbox renders; screenshot tests build it from FakeInboxData. */
@Immutable
data class InboxUiState(
    val rows: List<InboxMessage> = emptyList(),
    val unreadCount: Int = 0,
    /** Text in the search field. */
    val query: String = "",
    /** Non-null while server search results for this query are shown. */
    val searchedQuery: String? = null,
    val filter: InboxFilter = InboxFilter.All,
    val selectedId: String? = null,
    val detail: InboxMessage? = null,
    val detailLoading: Boolean = false,
    val loading: Boolean = false,
    val refreshing: Boolean = false,
    val loadingMore: Boolean = false,
    val hasMore: Boolean = false,
    val offlineSince: Instant? = null,
    val error: String? = null,
    val revoked: Boolean = false,
    val notificationsBlocked: Boolean = false,
    val canAdmin: Boolean = false,
    val canSend: Boolean = false,
    val hardwareKeyboard: Boolean = false,
    /** A short confirmation shown at the bottom (「已复制验证码」). */
    val toast: String? = null,
    /** Cover → inner unfold with an unread message: the envelope card (§10.4 ⑫). */
    val envelope: InboxMessage? = null,
    /** The cover-screen code card was copied (② flips to 「已复制 ✓」). */
    val copiedId: String? = null,
    /** The overflow tools and 在旁边打开 (off in the adjacent window). */
    val tools: Boolean = true,
    /** 多选: the picked message IDs; non-empty = selection mode (long-press a row). */
    val picked: Set<String> = emptySet(),
    /** 多选 entered from the overflow menu, possibly with nothing picked yet. */
    val selectMode: Boolean = false,
    /** A batch read change is with the server: its buttons wait. */
    val batchBusy: Boolean = false,
) {
    /** Rows after the filter chips and, before the search is submitted, the local text filter. */
    val visibleRows: List<InboxMessage>
        get() {
            val local = query.trim().takeIf { it.isNotEmpty() && searchedQuery == null }
            return rows.filter { row ->
                when (filter) {
                    InboxFilter.All -> true
                    InboxFilter.Unread -> !row.read
                    InboxFilter.Codes -> row.hasCode
                }
            }.filter { row ->
                local == null || listOf(row.displaySender, row.preview, row.sourceName, row.category)
                    .any { it.contains(local, ignoreCase = true) }
            }
        }

    /** The search text filters loaded rows only until it is submitted. */
    val filteringLocally: Boolean get() = query.isNotBlank() && searchedQuery == null

    /** Selection mode: the contextual bar replaces the title bar. */
    val selecting: Boolean get() = selectMode || picked.isNotEmpty()

    /** The picked rows the list shows (a filter change hides, and so excludes, the others). */
    val pickedRows: List<InboxMessage> get() = visibleRows.filter { it.id in picked }

    /**
     * 全部标为已读 marks what the list shows: with a search, the unread rows
     * shown; otherwise every unread message up to the newest one shown (later
     * arrivals stay unread), code messages only under 验证码. Null when there
     * is nothing to mark.
     */
    val markAllRead: BatchRead?
        get() {
            val shown = visibleRows
            if (shown.isEmpty() || batchBusy) return null
            if (query.isNotBlank() || searchedQuery != null) {
                val unread = shown.filter { !it.read }.map { it.id }
                return if (unread.isEmpty()) null else BatchRead.Ids(unread, true)
            }
            if (unreadCount <= 0 && shown.all { it.read }) return null
            return BatchRead.All(shown.first().id, codesOnly = filter == InboxFilter.Codes)
        }
}

/** UI events of the inbox. Defaults are no-ops so screenshot tests can omit them. */
@Immutable
data class InboxActions(
    val onOpen: (String) -> Unit = {},
    val onBack: () -> Unit = {},
    val onRefresh: () -> Unit = {},
    val onLoadMore: () -> Unit = {},
    val onQueryChange: (String) -> Unit = {},
    val onSearch: () -> Unit = {},
    val onClearSearch: () -> Unit = {},
    val onFilter: (InboxFilter) -> Unit = {},
    val onSetRead: (String, Boolean) -> Unit = { _, _ -> },
    val onCopyCode: (String) -> Unit = {},
    val onCopyBody: (String) -> Unit = {},
    val onOpenAdjacent: (String) -> Unit = {},
    val onOpenTool: (InboxSubScreen) -> Unit = {},
    val onEnableNotifications: () -> Unit = {},
    val onRepair: () -> Unit = {},
    val onDismissEnvelope: () -> Unit = {},
    /** Book posture edge paging (§10.4 ⑮): +1 next, -1 previous. */
    val onPage: (Int) -> Unit = {},
    /** 多选: long-press (or the 多选 accessibility action) picks a row; in selection mode a tap toggles it. */
    val onPick: (String) -> Unit = {},
    /** 全选 / 全不选 over the rows shown. */
    val onPickAll: (Boolean) -> Unit = {},
    /** Leaves selection mode (×, back, Esc). */
    val onClearPicks: () -> Unit = {},
    /** 标为已读 (true) / 标为未读 (false) for the picked rows shown. */
    val onMarkPicked: (Boolean) -> Unit = {},
    /** 全部标为已读 from the overflow menu ([InboxUiState.markAllRead]). */
    val onMarkAllRead: () -> Unit = {},
    /** 多选 from the overflow menu: selection mode with nothing picked yet. */
    val onStartSelect: () -> Unit = {},
)
