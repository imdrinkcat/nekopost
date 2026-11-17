package com.drinkcat.nekopost.ui.shell

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import com.drinkcat.nekopost.inbox.ui.InboxFilter
import com.drinkcat.nekopost.inbox.ui.InboxSubScreen
import com.drinkcat.nekopost.ui.log.LogKindFilter
import com.drinkcat.nekopost.ui.log.LogStatusFilter
import com.drinkcat.nekopost.ui.settings.AdminPage
import com.drinkcat.nekopost.ui.settings.SettingsItem

/**
 * UI state that must survive posture changes (design §10.6): selections,
 * filters, the search text and the log scroll position. It lives above the
 * posture-dependent layouts, so folding, unfolding or entering tabletop never
 * resets it, and it is saved across process death — IDs and enum names only,
 * never message content. The app lock keeps it too (the content stays composed).
 */
@Stable
class ShellMemory(
    selectedLogId: Long? = null,
    statusFilter: LogStatusFilter = LogStatusFilter.All,
    kindFilter: LogKindFilter = LogKindFilter.All,
    settingsItem: SettingsItem? = null,
    appQuery: String = "",
    backlogDismissedAt: Int = 0,
    debugUnlocked: Boolean = false,
    logFirstIndex: Int = 0,
    logFirstOffset: Int = 0,
    adminPage: AdminPage? = null,
    val inbox: InboxMemory = InboxMemory(),
) {
    /** Selected log row. On a single pane a non-null value means its detail is open. */
    var selectedLogId by mutableStateOf(selectedLogId)
    var statusFilter by mutableStateOf(statusFilter)
    var kindFilter by mutableStateOf(kindFilter)

    /** Selected settings entry. On a single pane a non-null value means its detail is open. */
    var settingsItem by mutableStateOf(settingsItem)
    var appQuery by mutableStateOf(appQuery)

    /** Page inside 设置 › 设备与管理 (null = its list). */
    var adminPage by mutableStateOf(adminPage)

    /** Pending count at which the backlog banner was dismissed; it returns if the backlog grows. */
    var backlogDismissedAt by mutableIntStateOf(backlogDismissedAt)
    var debugUnlocked by mutableStateOf(debugUnlocked)

    val logListState = LazyListState(logFirstIndex, logFirstOffset)

    companion object {
        val Saver: Saver<ShellMemory, Any> = listSaver(
            save = {
                listOf(
                    it.selectedLogId ?: -1L,
                    it.statusFilter.name,
                    it.kindFilter.name,
                    it.settingsItem?.name.orEmpty(),
                    it.appQuery,
                    it.backlogDismissedAt,
                    it.debugUnlocked,
                    it.logListState.firstVisibleItemIndex,
                    it.logListState.firstVisibleItemScrollOffset,
                    it.adminPage?.name.orEmpty(),
                    it.inbox.selectedId.orEmpty(),
                    it.inbox.query,
                    it.inbox.filter.name,
                    it.inbox.subScreen.name,
                    it.inbox.listState.firstVisibleItemIndex,
                    it.inbox.listState.firstVisibleItemScrollOffset,
                )
            },
            restore = { v ->
                ShellMemory(
                    selectedLogId = (v[0] as Long).takeIf { it >= 0 },
                    statusFilter = LogStatusFilter.valueOf(v[1] as String),
                    kindFilter = LogKindFilter.valueOf(v[2] as String),
                    // Entries renamed or removed in an update fall back to the default.
                    settingsItem = SettingsItem.entries.firstOrNull { it.name == v[3] as String },
                    appQuery = v[4] as String,
                    backlogDismissedAt = v[5] as Int,
                    debugUnlocked = v[6] as Boolean,
                    logFirstIndex = v[7] as Int,
                    logFirstOffset = v[8] as Int,
                    adminPage = AdminPage.entries.firstOrNull { it.name == v[9] as String },
                    inbox = InboxMemory(
                        selectedId = (v[10] as String).takeIf { it.isNotEmpty() },
                        query = v[11] as String,
                        filter = InboxFilter.entries.firstOrNull { it.name == v[12] as String } ?: InboxFilter.All,
                        subScreen = InboxSubScreen.entries.firstOrNull { it.name == v[13] as String } ?: InboxSubScreen.None,
                        firstIndex = v[14] as Int,
                        firstOffset = v[15] as Int,
                    ),
                )
            },
        )
    }
}

/** The inbox part of [ShellMemory]: survives tab switches, postures and the app lock. */
@Stable
class InboxMemory(
    selectedId: String? = null,
    query: String = "",
    filter: InboxFilter = InboxFilter.All,
    subScreen: InboxSubScreen = InboxSubScreen.None,
    firstIndex: Int = 0,
    firstOffset: Int = 0,
) {
    /** The open message. On a single pane a non-null value means its detail is shown. */
    var selectedId by mutableStateOf(selectedId)
    var query by mutableStateOf(query)
    var filter by mutableStateOf(filter)

    /** A tool reached from the overflow (分拣台, 值守台, 发给自己, 配对此手机, 键盘快捷键). */
    var subScreen by mutableStateOf(subScreen)
    val listState = LazyListState(firstIndex, firstOffset)

    /** 多选: the picked message IDs (empty = not selecting); kept across tabs and postures, not process death. */
    var picked by mutableStateOf(emptySet<String>())
}

@Composable
fun rememberShellMemory(): ShellMemory = rememberSaveable(saver = ShellMemory.Saver) { ShellMemory() }
