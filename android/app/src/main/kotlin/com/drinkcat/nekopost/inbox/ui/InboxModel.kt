package com.drinkcat.nekopost.inbox.ui

import androidx.compose.runtime.Immutable
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
)
