package com.drinkcat.nekopost.ui

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.and
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import com.drinkcat.nekopost.core.ui.fold.FoldState
import com.drinkcat.nekopost.inbox.FakeInboxData
import com.drinkcat.nekopost.inbox.ui.InboxActions
import com.drinkcat.nekopost.inbox.ui.InboxFilter
import com.drinkcat.nekopost.inbox.ui.InboxScreen
import com.drinkcat.nekopost.inbox.ui.InboxUiState
import com.drinkcat.nekopost.ui.shell.PaneMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** 多选 and 全部标为已读 in the 消息 list (批量已读, app 0.3.1). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class InboxSelectionTest {
    private val composeRule = createAndroidComposeRule<ComponentActivity>()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(RegisterComponentActivityRule()).around(composeRule)

    private var base by mutableStateOf(FakeInboxData.list)
    private var picked by mutableStateOf(emptySet<String>())
    private var selectedId by mutableStateOf<String?>(null)
    private var filter by mutableStateOf(InboxFilter.All)
    private val opened = mutableListOf<String>()
    private val marked = mutableListOf<Pair<Set<String>, Boolean>>()
    private var markAll = 0

    private val otp = FakeInboxData.otp.id
    private val wechat = FakeInboxData.wechat.id

    private fun ui(): InboxUiState = base.copy(
        picked = picked,
        selectedId = selectedId,
        detail = FakeInboxData.rows.firstOrNull { it.id == selectedId },
        filter = filter,
    )

    private fun show(fold: FoldState, mode: PaneMode) {
        composeRule.setContent {
            TestHarness {
                val ui = ui()
                InboxScreen(
                    ui,
                    fold,
                    mode,
                    InboxActions(
                        onOpen = { opened += it; selectedId = it },
                        onBack = { selectedId = null },
                        onFilter = { filter = it },
                        onPick = { id -> picked = if (id in picked) picked - id else picked + id },
                        onPickAll = { all -> picked = if (all) ui.visibleRows.map { it.id }.toSet() else emptySet() },
                        onClearPicks = { picked = emptySet() },
                        onMarkPicked = { read -> marked += ui.pickedRows.map { it.id }.toSet() to read },
                        onMarkAllRead = { markAll++ },
                    ),
                )
            }
        }
    }

    private fun back() {
        composeRule.runOnUiThread { composeRule.activity.onBackPressedDispatcher.onBackPressed() }
        composeRule.waitForIdle()
    }

    private fun row(id: String) = composeRule.onNodeWithTag("inbox_row_$id")

    /** The row's clickable node (it carries the accessibility actions and state). */
    private fun rowAction(id: String) = composeRule.onNode(
        hasAnyAncestor(hasTestTag("inbox_row_$id")) and SemanticsMatcher.keyIsDefined(SemanticsActions.OnClick),
    )

    @Test
    @Config(qualifiers = "w475dp-h751dp-port-420dpi")
    fun longPressPicksTapsToggleSelectAllAndBackLeaves() {
        show(FoldState.Cover, PaneMode.Single)
        row(otp).performTouchInput { longClick() }
        composeRule.onNodeWithTag("inbox_selection_bar").assertIsDisplayed()
        composeRule.onNodeWithText("已选择 1 条").assertIsDisplayed()
        composeRule.onNodeWithTag("picked_mark").assertIsDisplayed()

        // In selection mode a tap picks instead of opening.
        row(wechat).performClick()
        composeRule.onNodeWithText("已选择 2 条").assertIsDisplayed()
        assertTrue(opened.isEmpty())
        assertEquals(ToggleableState.On, rowAction(wechat).fetchSemanticsNode().config[SemanticsProperties.ToggleableState])

        composeRule.onNodeWithTag("inbox_select_all").performClick()
        composeRule.onNodeWithText("已选择 ${FakeInboxData.rows.size} 条").assertIsDisplayed()
        composeRule.onNodeWithText("全不选").assertIsDisplayed()

        composeRule.onNodeWithTag("inbox_picked_read").assertIsEnabled().performClick()
        assertEquals(FakeInboxData.rows.map { it.id }.toSet() to true, marked.last())
        composeRule.onNodeWithTag("inbox_picked_unread").performClick()
        assertEquals(false, marked.last().second)

        back()
        assertTrue(picked.isEmpty())
        composeRule.onNodeWithTag("inbox_selection_bar").assertDoesNotExist()
        composeRule.onNodeWithText("消息").assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = "w475dp-h751dp-port-420dpi")
    fun accessibilityActionStartsSelectionAndTheCloseButtonEndsIt() {
        show(FoldState.Cover, PaneMode.Single)
        // (A row with a code has a second clickable, the code chip, so the plain row is used.)
        val node = rowAction(wechat).fetchSemanticsNode()
        val select = node.config[SemanticsActions.CustomActions].first { it.label == "多选" }
        composeRule.runOnUiThread { select.action() }
        composeRule.waitForIdle()
        assertEquals(setOf(wechat), picked)
        assertEquals(ToggleableState.On, rowAction(wechat).fetchSemanticsNode().config[SemanticsProperties.ToggleableState])
        // While selecting, the code chip does not copy: the row is the only target.
        assertEquals(ToggleableState.Off, rowAction(otp).fetchSemanticsNode().config[SemanticsProperties.ToggleableState])

        // Only read rows picked: 标为已读 has nothing to do.
        picked = setOf(FakeInboxData.note.id)
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("inbox_picked_read").assertIsNotEnabled()
        composeRule.onNodeWithTag("inbox_picked_unread").assertIsEnabled()

        composeRule.onNodeWithTag("inbox_selection_close").performClick()
        assertTrue(picked.isEmpty())
    }

    @Test
    @Config(qualifiers = "w933dp-h704dp-land-420dpi")
    fun innerKeepsTheOpenMessageWhileSelecting() {
        selectedId = otp
        show(FoldState.Flat, PaneMode.TwoPane)
        row(wechat).performTouchInput { longClick() }
        composeRule.onNodeWithTag("inbox_selection_bar").assertIsDisplayed()
        composeRule.onNodeWithTag("inbox_detail_$otp").assertIsDisplayed()
        back()
        assertTrue(picked.isEmpty())
        assertEquals(otp, selectedId)
    }

    @Test
    @Config(qualifiers = "w475dp-h751dp-port-420dpi")
    fun onTheCoverBackClosesAnOpenMessageBeforeTheSelection() {
        picked = setOf(wechat)
        selectedId = otp
        show(FoldState.Cover, PaneMode.Single)
        back()
        assertEquals(null, selectedId)
        assertEquals(setOf(wechat), picked)
        composeRule.onNodeWithTag("inbox_selection_bar").assertIsDisplayed()
        back()
        assertTrue(picked.isEmpty())
    }

    @Test
    @Config(qualifiers = "w475dp-h751dp-port-420dpi")
    fun overflowMarksAllReadOnlyWhenThereIsSomethingToMark() {
        show(FoldState.Cover, PaneMode.Single)
        composeRule.onNodeWithTag("inbox_overflow").performClick()
        composeRule.onNodeWithTag("inbox_mark_all_read").assertIsEnabled().performClick()
        assertEquals(1, markAll)

        base = FakeInboxData.list.copy(rows = FakeInboxData.rows.map { it.copy(read = true) }, unreadCount = 0)
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("inbox_overflow").performClick()
        composeRule.onNodeWithTag("inbox_mark_all_read").assertIsNotEnabled()
    }
}
