package com.drinkcat.nekopost.inbox

import android.app.Application
import com.drinkcat.nekopost.inbox.ui.InboxFilter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** 批量已读: what is sent (CONTRACT §6, server 0.10.2) and what it does to the loaded rows. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class BatchReadTest {
    private val rows = FakeInboxData.rows
    private val list = FakeInboxData.list

    @Test
    fun markAllIsBoundedToTheNewestRowShownAndFollowsTheChip() {
        assertEquals(BatchRead.All(FakeInboxData.otp.id, codesOnly = false), list.markAllRead)
        // 未读: the newest unread row is the bound; the server only touches unread messages anyway.
        assertEquals(BatchRead.All(FakeInboxData.otp.id, codesOnly = false), list.copy(filter = InboxFilter.Unread).markAllRead)
        val codes = list.copy(filter = InboxFilter.Codes, rows = listOf(FakeInboxData.wechat, FakeInboxData.otp.copy(read = false)))
        assertEquals(BatchRead.All(FakeInboxData.otp.id, codesOnly = true), codes.markAllRead)
    }

    @Test
    fun markAllUnderASearchSendsTheUnreadRowsShown() {
        val searched = list.copy(query = "验证码", searchedQuery = "验证码", rows = listOf(FakeInboxData.otp, FakeInboxData.note))
        assertEquals(BatchRead.Ids(listOf(FakeInboxData.otp.id), true), searched.markAllRead)
        val local = list.copy(query = "张三")
        assertEquals(BatchRead.Ids(listOf(FakeInboxData.wechat.id), true), local.markAllRead)
        assertNull(list.copy(query = "妈妈").markAllRead)
    }

    @Test
    fun markAllIsOffWithNothingToMarkOrWhileBusy() {
        val allRead = rows.map { it.copy(read = true) }
        assertNull(list.copy(rows = allRead, unreadCount = 0).markAllRead)
        assertNull(list.copy(rows = emptyList(), unreadCount = 3).markAllRead)
        assertNull(list.copy(batchBusy = true).markAllRead)
        // Unread messages beyond the loaded page still count.
        assertEquals(BatchRead.All(allRead.first().id, false), list.copy(rows = allRead, unreadCount = 2).markAllRead)
    }

    @Test
    fun bodiesMatchTheContract() {
        val all = BatchRead.body(BatchRead.All(FakeInboxData.otp.id, codesOnly = true))
        assertEquals(setOf("all", "up_to_id", "has_code"), all.keys().asSequence().toSet())
        assertTrue(all.getBoolean("all"))
        assertEquals(FakeInboxData.otp.id, all.getString("up_to_id"))
        assertTrue(all.getBoolean("has_code"))
        val ids = BatchRead.body(BatchRead.Ids(listOf(FakeInboxData.otp.id, FakeInboxData.ci.id), false))
        assertEquals(setOf("ids"), ids.keys().asSequence().toSet())
        assertEquals(2, ids.getJSONArray("ids").length())
        assertEquals(listOf("v1", "inbox", "read"), BatchRead.path(BatchRead.All(FakeInboxData.otp.id, false)))
        assertEquals(listOf("v1", "inbox", "unread"), BatchRead.path(BatchRead.Ids(listOf(FakeInboxData.otp.id), false)))
    }

    @Test
    fun invalidIdsAreRejectedAndLargeSelectionsChunked() {
        assertFalse(runCatching { BatchRead.Ids(listOf("m_bad"), true) }.isSuccess)
        assertFalse(runCatching { BatchRead.Ids(emptyList(), true) }.isSuccess)
        assertFalse(runCatching { BatchRead.All("x", false) }.isSuccess)
        val many = (1..1203).map(FakeInboxData::id)
        val chunks = BatchRead.chunks(many + many.take(3) + "m_bad", read = true)
        assertEquals(listOf(500, 500, 203), chunks.map { it.ids.size })
        assertEquals(many, chunks.flatMap { it.ids })
        assertTrue(chunks.all { it.read })
    }

    @Test
    fun allMarksFromTheBoundDownOnly() {
        // Bound = ci (third row): otp and wechat are newer and stay as they were.
        val local = BatchRead.apply(rows, BatchRead.All(FakeInboxData.ci.id, codesOnly = false))
        assertFalse(local.rows.first { it.id == FakeInboxData.otp.id }.read)
        assertFalse(local.rows.first { it.id == FakeInboxData.wechat.id }.read)
        assertTrue(local.rows.drop(2).all { it.read })
        assertEquals(2, local.changed) // ci and long were unread
        assertEquals(rows.drop(2).map { it.id }, local.covered)
    }

    @Test
    fun allWithCodesOnlyLeavesOtherRows() {
        val local = BatchRead.apply(rows, BatchRead.All(FakeInboxData.otp.id, codesOnly = true))
        assertTrue(local.rows.first { it.id == FakeInboxData.otp.id }.read)
        assertFalse(local.rows.first { it.id == FakeInboxData.wechat.id }.read)
        assertEquals(1, local.changed)
        assertEquals(listOf(FakeInboxData.otp.id), local.covered)
    }

    @Test
    fun anUnloadedBoundChangesNothingLocally() {
        val local = BatchRead.apply(rows, BatchRead.All(FakeInboxData.id(99), codesOnly = false))
        assertEquals(rows, local.rows)
        assertEquals(0, local.changed)
    }

    @Test
    fun idsMarkReadAndUnreadAndAreIdempotent() {
        val read = BatchRead.apply(rows, BatchRead.Ids(listOf(FakeInboxData.otp.id, FakeInboxData.note.id, FakeInboxData.id(99)), true))
        assertEquals(1, read.changed) // note was already read
        assertTrue(read.rows.first { it.id == FakeInboxData.otp.id }.read)
        // Unloaded IDs are still covered: their notifications are cleared too.
        assertTrue(FakeInboxData.id(99) in read.covered)
        assertEquals(0, BatchRead.apply(read.rows, BatchRead.Ids(listOf(FakeInboxData.otp.id), true)).changed)

        val unread = BatchRead.apply(read.rows, BatchRead.Ids(listOf(FakeInboxData.otp.id, FakeInboxData.note.id), false))
        assertEquals(2, unread.changed)
        assertFalse(unread.rows.first { it.id == FakeInboxData.note.id }.read)
    }

    @Test
    fun replyIdsReachMessagesThatWereNeverLoaded() {
        // 全部标为已读 covers unread messages below the loaded page; their
        // notifications are cleared from the IDs the server returns.
        val older = "m_01ARZ3NDEKTSV4RRFFQ69G5FAV"
        val reply = org.json.JSONObject("""{"changed":3,"unread_count":0,"ids":["${FakeInboxData.otp.id}","$older","m_bad",7]}""")
        assertEquals(listOf(FakeInboxData.otp.id, older), BatchRead.replyIds(reply))
        // An older server sends no IDs: only the loaded rows are cleared.
        assertTrue(BatchRead.replyIds(org.json.JSONObject("""{"changed":0,"unread_count":0}""")).isEmpty())
    }

    @Test
    fun selectingFromTheMenuStartsWithNothingPicked() {
        val ui = list.copy(selectMode = true)
        assertTrue(ui.selecting)
        assertTrue(ui.pickedRows.isEmpty())
        assertFalse(list.selecting)
    }
}
