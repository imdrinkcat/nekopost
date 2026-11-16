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
import com.drinkcat.nekopost.ui.log.LogKindFilter
import com.drinkcat.nekopost.ui.log.LogStatusFilter
import com.drinkcat.nekopost.ui.settings.SettingsItem

/**
 * UI state that must survive posture changes (design §10.6): selections,
 * filters, the search text and the log scroll position. It lives above the
 * posture-dependent layouts, so folding, unfolding or entering tabletop never
 * resets it, and it is saved across process death — IDs and enum names only,
 * never message content.
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
) {
    /** Selected log row. On a single pane a non-null value means its detail is open. */
    var selectedLogId by mutableStateOf(selectedLogId)
    var statusFilter by mutableStateOf(statusFilter)
    var kindFilter by mutableStateOf(kindFilter)

    /** Selected settings entry. On a single pane a non-null value means its detail is open. */
    var settingsItem by mutableStateOf(settingsItem)
    var appQuery by mutableStateOf(appQuery)

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
                )
            },
            restore = { v ->
                ShellMemory(
                    selectedLogId = (v[0] as Long).takeIf { it >= 0 },
                    statusFilter = LogStatusFilter.valueOf(v[1] as String),
                    kindFilter = LogKindFilter.valueOf(v[2] as String),
                    settingsItem = (v[3] as String).takeIf { it.isNotEmpty() }?.let { SettingsItem.valueOf(it) },
                    appQuery = v[4] as String,
                    backlogDismissedAt = v[5] as Int,
                    debugUnlocked = v[6] as Boolean,
                    logFirstIndex = v[7] as Int,
                    logFirstOffset = v[8] as Int,
                )
            },
        )
    }
}

@Composable
fun rememberShellMemory(): ShellMemory = rememberSaveable(saver = ShellMemory.Saver) { ShellMemory() }
