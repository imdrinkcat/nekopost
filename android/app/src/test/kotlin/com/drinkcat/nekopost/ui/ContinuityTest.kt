package com.drinkcat.nekopost.ui

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import com.drinkcat.nekopost.core.forwarder.FakeForwarderData
import com.drinkcat.nekopost.core.ui.fold.FoldState
import com.drinkcat.nekopost.core.ui.token.NekoTokens
import com.drinkcat.nekopost.inbox.FakeInboxData
import com.drinkcat.nekopost.inbox.ui.InboxActions
import com.drinkcat.nekopost.inbox.ui.InboxFilter
import com.drinkcat.nekopost.inbox.ui.InboxScreen
import com.drinkcat.nekopost.inbox.ui.InboxUiState
import com.drinkcat.nekopost.lock.LockGate
import com.drinkcat.nekopost.lock.LockReason
import com.drinkcat.nekopost.lock.LockState
import com.drinkcat.nekopost.ui.log.LogKindFilter
import com.drinkcat.nekopost.ui.log.LogStatusFilter
import com.drinkcat.nekopost.ui.settings.SettingsItem
import com.drinkcat.nekopost.ui.setup.EnrollUiState
import com.drinkcat.nekopost.ui.setup.SetupActions
import com.drinkcat.nekopost.ui.setup.SetupStep
import com.drinkcat.nekopost.ui.setup.SetupWizard
import com.drinkcat.nekopost.ui.setup.visibleSteps
import com.drinkcat.nekopost.ui.shell.Destination
import com.drinkcat.nekopost.ui.shell.NekopostShell
import com.drinkcat.nekopost.ui.shell.ShellActions
import com.drinkcat.nekopost.ui.shell.ShellMemory
import com.drinkcat.nekopost.ui.shell.ShellUiState
import com.drinkcat.nekopost.ui.shell.rememberShellMemory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Continuity across postures (design §10.6/§10.10): the posture is switched
 * in place, as on the device where MainActivity handles the configuration
 * change itself, and selections, filters and details must survive.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class ContinuityTest {
    private val composeRule = createAndroidComposeRule<ComponentActivity>()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(RegisterComponentActivityRule()).around(composeRule)

    private val state = ShellUiState(
        forwarder = FakeForwarderData.enrolledState,
        log = FakeForwarderData.log,
        otp = FakeForwarderData.otpHealthy,
        appVersion = "0.1.0",
        apps = Samples.apps,
    )

    private var fold by mutableStateOf(FoldState.Cover)
    private var destination by mutableStateOf(Destination.Log)
    private val memory = ShellMemory()

    private var lock by mutableStateOf<LockState>(LockState.Unlocked)

    /** The inbox as the root drives it: selection lives in ShellMemory, nowhere else. */
    private fun inboxState(): InboxUiState {
        val id = memory.inbox.selectedId
        val detail = FakeInboxData.rows.firstOrNull { it.id == id }?.let { if (it.id == FakeInboxData.otp.id) FakeInboxData.otpDetail else it }
        return FakeInboxData.list.copy(selectedId = id, detail = detail, query = memory.inbox.query, filter = memory.inbox.filter)
    }

    private fun show() {
        composeRule.setContent {
            TestHarness {
                LockGate(lock, fold, issue = null, onUnlock = {}) {
                    NekopostShell(
                        state = state,
                        fold = fold,
                        destination = destination,
                        onDestinationChange = { destination = it },
                        actions = ShellActions(),
                        memory = memory,
                        inboxContent = { mode ->
                            InboxScreen(
                                inboxState(),
                                fold,
                                mode,
                                InboxActions(
                                    onOpen = { memory.inbox.selectedId = it },
                                    onBack = { memory.inbox.selectedId = null },
                                    onFilter = { memory.inbox.filter = it },
                                ),
                                listState = memory.inbox.listState,
                            )
                        },
                    )
                }
            }
        }
    }

    @Test
    @Config(qualifiers = "w933dp-h704dp-land-420dpi")
    fun inboxSelectionFollowsPosturesAndTheLock() {
        destination = Destination.Inbox
        show()
        val id = FakeInboxData.otp.id
        // Cover: a tap opens the message full screen.
        composeRule.onNodeWithTag("inbox_row_$id").performClick()
        composeRule.onNodeWithTag("inbox_detail_$id").assertIsDisplayed()
        composeRule.onNodeWithTag("otp_card").assertIsDisplayed()

        // Unfold: list and the same message side by side (① 展开即达).
        fold = FoldState.Flat
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("inbox_two_pane").assertExists()
        composeRule.onNodeWithTag("inbox_detail_$id").assertIsDisplayed()
        composeRule.onNodeWithTag("inbox_row_$id").assertIsDisplayed()

        // Tabletop: the message above the hinge, the list below.
        fold = FoldState.tabletop()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("inbox_tabletop").assertExists()
        composeRule.onNodeWithTag("inbox_detail_$id").assertIsDisplayed()

        // The lock hides it and gives it back unchanged.
        composeRule.onNodeWithTag("inbox_filter_Codes").performClick()
        lock = LockState.Locked(LockReason.Background)
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("inbox_detail_$id").assertDoesNotExist()
        lock = LockState.Unlocked
        fold = FoldState.Cover
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("inbox_detail_$id").assertIsDisplayed()
        assertEquals(id, memory.inbox.selectedId)
        assertEquals(InboxFilter.Codes, memory.inbox.filter)
    }

    @Test
    @Config(qualifiers = "w933dp-h704dp-land-420dpi")
    fun logSelectionFollowsCoverFlatTabletopAndBack() {
        show()
        // Cover: tapping a row opens its detail full screen.
        composeRule.onNodeWithTag("log_row_5").performClick()
        composeRule.onNodeWithTag("log_detail_5").assertIsDisplayed()

        // Unfold: list and the same detail side by side, zero taps.
        fold = FoldState.Flat
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("log_two_pane").assertExists()
        composeRule.onNodeWithTag("log_detail_5").assertIsDisplayed()
        composeRule.onNodeWithTag("log_row_5").assertIsDisplayed()

        // Tabletop: the selected item moves to the upper half.
        fold = FoldState.tabletop()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("log_tabletop").assertExists()
        composeRule.onNodeWithTag("log_detail_5").assertIsDisplayed()

        // Fold again: the cover still shows the same item.
        fold = FoldState.Cover
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("log_detail_5").assertIsDisplayed()
        assertEquals(5L, memory.selectedLogId)
    }

    @Test
    @Config(qualifiers = "w933dp-h704dp-land-420dpi")
    fun filtersAndSettingsSurvivePostureChanges() {
        fold = FoldState.Flat
        show()
        composeRule.onNodeWithTag("filter_Failed").performClick()
        composeRule.onNodeWithTag("log_row_1").assertExists()
        composeRule.onNodeWithTag("log_row_5").assertDoesNotExist()

        fold = FoldState.Cover
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("log_row_1").assertExists()
        composeRule.onNodeWithTag("log_row_5").assertDoesNotExist()
        assertEquals(LogStatusFilter.Failed, memory.statusFilter)

        destination = Destination.Settings
        fold = FoldState.Flat
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("settings_Apps").performClick()
        composeRule.onNodeWithTag("settings_detail_Apps").assertIsDisplayed()

        fold = FoldState.Cover
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("settings_detail_Apps").assertIsDisplayed()
        assertEquals(SettingsItem.Apps, memory.settingsItem)
    }

    @Test
    fun memorySurvivesProcessDeath() {
        val tester = StateRestorationTester(composeRule)
        var restored: ShellMemory? = null
        tester.setContent {
            val m = rememberShellMemory()
            restored = m
        }
        restored!!.apply {
            selectedLogId = 4
            statusFilter = LogStatusFilter.Delivered
            kindFilter = LogKindFilter.Notification
            settingsItem = SettingsItem.SmsRules
            appQuery = "line"
            inbox.selectedId = FakeInboxData.otp.id
            inbox.query = "银行"
            inbox.filter = InboxFilter.Unread
        }
        tester.emulateSavedInstanceStateRestore()
        val m = restored!!
        assertEquals(4L, m.selectedLogId)
        assertEquals(LogStatusFilter.Delivered, m.statusFilter)
        assertEquals(LogKindFilter.Notification, m.kindFilter)
        assertEquals(SettingsItem.SmsRules, m.settingsItem)
        assertEquals("line", m.appQuery)
        assertEquals(FakeInboxData.otp.id, m.inbox.selectedId)
        assertEquals("银行", m.inbox.query)
        assertEquals(InboxFilter.Unread, m.inbox.filter)
    }

    /**
     * Automated hinge rule (design §10.2): in tabletop nothing clickable may lie
     * within ±24dp of the hinge (assumed at the window centre without bounds).
     */
    @Test
    @Config(qualifiers = "w704dp-h933dp-port-420dpi")
    fun tabletopKeepsClickablesAwayFromHinge() {
        fold = FoldState.tabletop()
        show()
        for (d in Destination.entries) {
            destination = d
            assertNoClickableOnHinge("$d")
        }
    }

    /** The same rule for every wizard step, which has its own tabletop layout. */
    @Test
    @Config(qualifiers = "w704dp-h933dp-port-420dpi")
    fun tabletopWizardKeepsClickablesAwayFromHinge() {
        var step by mutableStateOf(SetupStep.Welcome)
        val snapshot = Samples.snapshot(enrolled = true, smsBlocked = true)
        composeRule.setContent {
            TestHarness {
                SetupWizard(
                    snapshot = snapshot,
                    step = step,
                    enroll = EnrollUiState.Idle,
                    fold = FoldState.tabletop(),
                    actions = SetupActions(),
                    canClose = true,
                )
            }
        }
        for (s in snapshot.visibleSteps()) {
            step = s
            assertNoClickableOnHinge("wizard $s")
        }
    }

    private fun assertNoClickableOnHinge(where: String) {
        composeRule.waitForIdle()
        val density = composeRule.activity.resources.displayMetrics.density
        val windowHeight = composeRule.activity.window.decorView.height
        val centre = windowHeight / 2f
        val keepOut = NekoTokens.Hinge.keepOut.value * density
        val clickable = composeRule
            .onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.OnClick), useUnmergedTree = true)
            .fetchSemanticsNodes()
        assertTrue("no clickables found on $where", clickable.isNotEmpty())
        clickable.forEach { node ->
            val b = node.boundsInWindow
            val intersects = b.bottom > centre - keepOut && b.top < centre + keepOut && b.height > 0f
            assertTrue("clickable ${node.config} at $b crosses the hinge band on $where", !intersects)
        }
    }
}
