package com.drinkcat.nekopost.inbox

import org.json.JSONArray
import org.json.JSONObject

/**
 * One batch read-state change (CONTRACT §6, server 0.10.2:
 * `POST /v1/inbox/read` and `POST /v1/inbox/unread`).
 */
sealed interface BatchRead {
    /** true: mark read; false: mark unread (ids only). */
    val read: Boolean

    /**
     * 全部标为已读: every unread message not newer than [upToId], the newest
     * message the list shows, so letters arriving after the tap stay unread.
     * [codesOnly] follows the 验证码 chip.
     */
    data class All(val upToId: String, val codesOnly: Boolean) : BatchRead {
        init {
            require(messagePattern.matches(upToId))
        }

        override val read: Boolean get() = true
    }

    /** These messages (multi-select, or the rows a search shows); sent in [chunks] of at most [MAX_IDS]. */
    data class Ids(val ids: List<String>, override val read: Boolean) : BatchRead {
        init {
            require(ids.isNotEmpty() && ids.all(messagePattern::matches))
        }
    }

    /** What a successful change does to the loaded rows. */
    data class Local(
        val rows: List<InboxMessage>,
        /** Rows that changed state (for the unread count when the server sends none). */
        val changed: Int,
        /** Every loaded message the change covered: their notifications are cleared after a read. */
        val covered: List<String>,
    )

    companion object {
        /** The server's per-request limit. */
        const val MAX_IDS = 500

        /** [ids] as requests of at most [MAX_IDS], duplicates and malformed IDs dropped. */
        fun chunks(ids: Collection<String>, read: Boolean): List<Ids> =
            ids.distinct().filter(messagePattern::matches).chunked(MAX_IDS).map { Ids(it, read) }

        fun path(change: BatchRead): List<String> = listOf("v1", "inbox", if (change.read) "read" else "unread")

        fun body(change: BatchRead): JSONObject = when (change) {
            is All -> JSONObject().put("all", true).put("up_to_id", change.upToId).put("has_code", change.codesOnly)
            is Ids -> JSONObject().put("ids", JSONArray(change.ids))
        }

        /**
         * Applies an accepted [change] to [rows] (newest first, as the server
         * lists them). For [All] that is every row from the bound down, code rows
         * only with [All.codesOnly]; a bound that is not loaded changes nothing
         * here (the refresh that follows catches up).
         */
        fun apply(rows: List<InboxMessage>, change: BatchRead): Local {
            val inScope: (Int, InboxMessage) -> Boolean = when (change) {
                is All -> {
                    val bound = rows.indexOfFirst { it.id == change.upToId }
                    { index, row -> bound >= 0 && index >= bound && (!change.codesOnly || row.hasCode) }
                }
                is Ids -> {
                    val wanted = change.ids.toHashSet()
                    { _, row -> row.id in wanted }
                }
            }
            var changed = 0
            val covered = mutableListOf<String>()
            val next = rows.mapIndexed { index, row ->
                if (!inScope(index, row)) return@mapIndexed row
                covered += row.id
                if (row.read == change.read) row else row.copy(read = change.read).also { changed++ }
            }
            val extra = if (change is Ids) change.ids.filter { it !in covered } else emptyList()
            return Local(next, changed, covered + extra)
        }
    }
}
