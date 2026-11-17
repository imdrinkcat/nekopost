package com.drinkcat.nekopost.ui

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import com.drinkcat.nekopost.core.forwarder.FakeForwarderData
import com.drinkcat.nekopost.core.ui.fold.FoldState
import com.drinkcat.nekopost.inbox.DropNoteContent
import com.drinkcat.nekopost.inbox.FakeInboxData
import com.drinkcat.nekopost.inbox.PairActions
import com.drinkcat.nekopost.inbox.PairThisPhonePane
import com.drinkcat.nekopost.inbox.PairUi
import com.drinkcat.nekopost.inbox.SortingDeskContent
import com.drinkcat.nekopost.inbox.SortingUi
import com.drinkcat.nekopost.inbox.WatchDeskContent
import com.drinkcat.nekopost.inbox.WatchUi
import com.drinkcat.nekopost.inbox.ui.InboxActions
import com.drinkcat.nekopost.inbox.ui.InboxScreen
import com.drinkcat.nekopost.inbox.ui.InboxSubScreen
import com.drinkcat.nekopost.inbox.ui.InboxUnpaired
import com.drinkcat.nekopost.inbox.ui.KeyboardShortcutsPane
import com.drinkcat.nekopost.lock.LocalAppLock
import com.drinkcat.nekopost.lock.LockReason
import com.drinkcat.nekopost.lock.LockState
import com.drinkcat.nekopost.lock.LockUiState
import com.drinkcat.nekopost.ui.settings.AdminActions
import com.drinkcat.nekopost.ui.settings.AdminPage
import com.drinkcat.nekopost.ui.settings.LockSettingsUi
import com.drinkcat.nekopost.ui.settings.SettingsActions
import com.drinkcat.nekopost.ui.settings.SettingsItem
import com.drinkcat.nekopost.ui.setup.EnrollUiState
import com.drinkcat.nekopost.ui.setup.SetupActions
import com.drinkcat.nekopost.ui.setup.SetupStep
import com.drinkcat.nekopost.ui.setup.SetupWizard
import com.drinkcat.nekopost.ui.setup.previousStep
import com.drinkcat.nekopost.ui.shell.Destination
import com.drinkcat.nekopost.ui.shell.NekopostShell
import com.drinkcat.nekopost.ui.shell.PaneMode
import com.drinkcat.nekopost.ui.shell.ShellActions
import com.drinkcat.nekopost.ui.shell.ShellMemory
import com.drinkcat.nekopost.ui.shell.ShellUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * System back walks out one level at a time (0.3.2: back used to close the
 * app from any sub-page): innermost level first, then the start tab, and
 * only from the start tab's root does the app exit. "Exit" is observed with a
 * probe registered before the content, so it only runs when no level of the
 * app took the back press (what the system would then do: finish / go home).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class BackNavigationTest {
    private val composeRule = createAndroidComposeRule<ComponentActivity>()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(RegisterComponentActivityRule()).around(composeRule)

    private val reader = ShellUiState(
        forwarder = FakeForwarderData.enrolledState,
        log = FakeForwarderData.log,
        otp = FakeForwarderData.otpHealthy,
        appVersion = "0.3.2",
        apps = Samples.apps,
        device = Samples.thisDevice,
        lock = LockSettingsUi(foldable = true),
    )
    private val admin = reader.copy(admin = Samples.admin)

    private var state by mutableStateOf(admin)
    private var destination by mutableStateOf(Destination.Inbox)
    private var lock by mutableStateOf<LockState>(LockState.Unlocked)
    private var memory = ShellMemory()
    private var exits = 0
    private var closedPairing = 0

    private fun installExitProbe() {
        composeRule.runOnUiThread {
            composeRule.activity.onBackPressedDispatcher.addCallback(
                object : OnBackPressedCallback(true) {
                    override fun handleOnBackPressed() {
                        exits++
                    }
                },
            )
        }
    }

    private fun show(content: @Composable () -> Unit) {
        installExitProbe()
        composeRule.setContent {
            CompositionLocalProvider(LocalAppLock provides LockUiState(lock, 1)) {
                TestHarness(content = content)
            }
        }
        composeRule.waitForIdle()
    }

    /** Whether the hand-written 消息 renders as InboxRoute does for an unpaired phone. */
    private var unpaired = false

    /**
     * The shell as NekopostRoot drives it; 消息 renders the tool sub-screens the way InboxRoute does.
     * [startDestination] is NekopostRoot's: 消息 when this phone can read messages, otherwise 转发.
     */
    private fun showShell(fold: FoldState, startDestination: Destination = Destination.Inbox) = show {
        NekopostShell(
            state = state,
            fold = fold,
            destination = destination,
            onDestinationChange = { destination = it },
            memory = memory,
            actions = ShellActions(
                settings = SettingsActions(admin = AdminActions(onClosePairing = { closedPairing++ })),
            ),
            startDestination = startDestination,
            inboxContent = { mode -> Inbox(fold, mode) },
        )
    }

    @Composable
    private fun Inbox(fold: FoldState, mode: PaneMode) {
        val inbox = memory.inbox
        val close = { inbox.subScreen = InboxSubScreen.None }
        if (unpaired) {
            if (inbox.subScreen == InboxSubScreen.Pair) {
                PairThisPhonePane(PairUi(name = "Fold8"), PairActions(onBack = close))
            } else {
                InboxUnpaired(revoked = false, onPair = { inbox.subScreen = InboxSubScreen.Pair })
            }
            return
        }
        when (inbox.subScreen) {
            InboxSubScreen.Sorting -> SortingDeskContent(SortingUi(FakeInboxData.note), fold, {}, {}, {}, {}, onBack = close)
            InboxSubScreen.Watch -> WatchDeskContent(WatchUi(FakeInboxData.rows, connected = true), fold, {}, {}, {}, onExit = close)
            InboxSubScreen.DropNote -> DropNoteContent("", {}, false, {}, null, false, {}, onBack = close)
            InboxSubScreen.Shortcuts -> KeyboardShortcutsPane(onBack = close)
            InboxSubScreen.Pair -> PairThisPhonePane(PairUi(name = "Fold8"), PairActions(onBack = close))
            InboxSubScreen.None -> {
                val id = inbox.selectedId
                InboxScreen(
                    FakeInboxData.list.copy(
                        selectedId = id,
                        detail = FakeInboxData.rows.firstOrNull { it.id == id },
                        picked = inbox.picked,
                        selectMode = inbox.selectMode,
                    ),
                    fold,
                    mode,
                    InboxActions(
                        onOpen = { inbox.selectedId = it },
                        onBack = { inbox.selectedId = null },
                        onClearPicks = { inbox.picked = emptySet(); inbox.selectMode = false },
                    ),
                )
            }
        }
    }

    private fun back() {
        composeRule.runOnUiThread { composeRule.activity.onBackPressedDispatcher.onBackPressed() }
        composeRule.waitForIdle()
    }

    /** Back stayed inside the app (the probe saw nothing: the system would neither finish nor go home). */
    private fun assertStayed() {
        assertEquals("back left the app too early", 0, exits)
    }

    private fun assertExits() {
        back()
        assertEquals("back at the start tab's root leaves the app", 1, exits)
    }

    @Test
    @Config(qualifiers = "w475dp-h751dp-port-420dpi")
    fun coverSettingsAdminPageThenListThenStartTab() {
        destination = Destination.Settings
        memory = ShellMemory(settingsItem = SettingsItem.Admin, adminPage = AdminPage.Keys)
        showShell(FoldState.Cover)

        back()
        assertEquals(null, memory.adminPage)
        assertEquals(SettingsItem.Admin, memory.settingsItem)
        assertEquals(Destination.Settings, destination)
        composeRule.onNodeWithTag("admin_home").assertExists()
        assertStayed()

        back()
        assertEquals(null, memory.settingsItem)
        assertEquals(Destination.Settings, destination)
        composeRule.onNodeWithTag("settings_single").assertExists()
        assertStayed()

        back()
        assertEquals(Destination.Inbox, destination)
        assertStayed()

        assertExits()
    }

    @Test
    @Config(qualifiers = "w475dp-h751dp-port-420dpi")
    fun coverAddDeviceReturnsToDevices() {
        destination = Destination.Settings
        memory = ShellMemory(settingsItem = SettingsItem.Admin, adminPage = AdminPage.AddDevice)
        showShell(FoldState.Cover)

        back()
        assertEquals(AdminPage.Devices, memory.adminPage)
        assertEquals(1, closedPairing)
        back()
        assertEquals(null, memory.adminPage)
        back()
        assertEquals(null, memory.settingsItem)
        back()
        assertEquals(Destination.Inbox, destination)
        assertStayed()
        assertExits()
    }

    @Test
    @Config(qualifiers = "w475dp-h751dp-port-420dpi")
    fun leavingALivePairingAsksFirst() {
        state = admin.copy(admin = Samples.admin.copy(pairing = Samples.pairingClaimed))
        destination = Destination.Settings
        memory = ShellMemory(settingsItem = SettingsItem.Admin, adminPage = AdminPage.AddDevice)
        showShell(FoldState.Cover)

        back()
        composeRule.onNodeWithTag("pairing_leave_stay").assertExists().performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("pairing_leave_stay").assertDoesNotExist()
        assertEquals(AdminPage.AddDevice, memory.adminPage)
        assertEquals(0, closedPairing)

        // The back arrow asks the same question.
        composeRule.onNodeWithTag("subscreen_back").performClick()
        composeRule.onNodeWithTag("pairing_leave_confirm").assertExists().performClick()
        composeRule.waitForIdle()
        assertEquals(AdminPage.Devices, memory.adminPage)
        assertEquals(1, closedPairing)
        assertStayed()
    }

    @Test
    @Config(qualifiers = "w475dp-h751dp-port-420dpi")
    fun coverLogDetailThenStartTab() {
        destination = Destination.Log
        memory = ShellMemory(selectedLogId = FakeForwarderData.log.first().id)
        showShell(FoldState.Cover)

        back()
        assertEquals(null, memory.selectedLogId)
        assertEquals(Destination.Log, destination)
        back()
        assertEquals(Destination.Inbox, destination)
        assertStayed()
        assertExits()
    }

    @Test
    @Config(qualifiers = "w475dp-h751dp-port-420dpi")
    fun coverForwardTabReturnsToTheStartTab() {
        destination = Destination.Forward
        showShell(FoldState.Cover)
        back()
        assertEquals(Destination.Inbox, destination)
        assertStayed()
        assertExits()
    }

    @Test
    @Config(qualifiers = "w475dp-h751dp-port-420dpi")
    fun coverInboxMessageThenToolsReturnToTheInbox() {
        memory = ShellMemory()
        memory.inbox.selectedId = FakeInboxData.otp.id
        showShell(FoldState.Cover)

        back()
        assertEquals(null, memory.inbox.selectedId)
        assertStayed()

        listOf(
            InboxSubScreen.Sorting to "sorting_desk",
            InboxSubScreen.Watch to "watch_desk",
            InboxSubScreen.DropNote to "drop_note",
            InboxSubScreen.Shortcuts to "keyboard_shortcuts",
            InboxSubScreen.Pair to "pair_this_phone",
        ).forEach { (tool, tag) ->
            composeRule.runOnUiThread { memory.inbox.subScreen = tool }
            composeRule.waitForIdle()
            composeRule.onNodeWithTag(tag).assertExists()
            back()
            assertEquals("back from $tool", InboxSubScreen.None, memory.inbox.subScreen)
            assertEquals(Destination.Inbox, destination)
            assertStayed()
        }
        assertExits()
    }

    @Test
    @Config(qualifiers = "w933dp-h704dp-land-420dpi")
    fun innerTwoPaneTabsReturnToTheStartTabKeepingTheirSelection() {
        destination = Destination.Settings
        memory = ShellMemory(settingsItem = SettingsItem.AppLock, selectedLogId = FakeForwarderData.log.first().id)
        showShell(FoldState.Flat)
        composeRule.onNodeWithTag("settings_two_pane").assertExists()

        back()
        assertEquals(Destination.Inbox, destination)
        // Two panes: the selection is part of the tab and stays for the next visit.
        assertEquals(SettingsItem.AppLock, memory.settingsItem)
        assertStayed()

        composeRule.runOnUiThread { destination = Destination.Log }
        composeRule.waitForIdle()
        back()
        assertEquals(Destination.Inbox, destination)
        assertEquals(FakeForwarderData.log.first().id, memory.selectedLogId)
        assertStayed()

        // An open message next to the list is the 消息 tab's root on the inner display.
        composeRule.runOnUiThread { memory.inbox.selectedId = FakeInboxData.otp.id }
        composeRule.waitForIdle()
        assertExits()
    }

    @Test
    @Config(qualifiers = "w933dp-h704dp-land-420dpi")
    fun innerAdminPageAndToolStillGoUpOneLevel() {
        destination = Destination.Settings
        memory = ShellMemory(settingsItem = SettingsItem.Admin, adminPage = AdminPage.Audit)
        showShell(FoldState.Flat)

        back()
        assertEquals(null, memory.adminPage)
        assertEquals(Destination.Settings, destination)
        back()
        assertEquals(Destination.Inbox, destination)

        composeRule.runOnUiThread { memory.inbox.subScreen = InboxSubScreen.Sorting }
        composeRule.waitForIdle()
        back()
        assertEquals(InboxSubScreen.None, memory.inbox.subScreen)
        assertStayed()
        assertExits()
    }

    @Test
    @Config(qualifiers = "w704dp-h933dp-port-420dpi")
    fun tabletopSettingsDetailThenStartTab() {
        destination = Destination.Settings
        memory = ShellMemory(settingsItem = SettingsItem.Notifications)
        showShell(FoldState.tabletop())

        back()
        assertEquals(null, memory.settingsItem)
        back()
        assertEquals(Destination.Inbox, destination)
        assertStayed()
        assertExits()
    }

    @Test
    @Config(qualifiers = "w475dp-h751dp-port-420dpi")
    fun lockedBackLeavesWithoutTouchingTheHiddenScreens() {
        lock = LockState.Locked(LockReason.Manual)
        destination = Destination.Settings
        memory = ShellMemory(settingsItem = SettingsItem.Admin, adminPage = AdminPage.Keys)
        showShell(FoldState.Cover)

        back()
        assertEquals(1, exits)
        assertEquals(AdminPage.Keys, memory.adminPage)
        assertEquals(Destination.Settings, destination)

        composeRule.runOnUiThread { lock = LockState.Unlocked }
        composeRule.waitForIdle()
        back()
        assertEquals(1, exits)
        assertEquals(null, memory.adminPage)
    }

    @Test
    @Config(qualifiers = "w475dp-h751dp-port-420dpi")
    fun pairThisPhoneLeavesTheConfirmationFirst() {
        var ui by mutableStateOf(PairUi(name = "Fold8", confirmHost = "osaka.drinkcat.com"))
        var left = 0
        show {
            PairThisPhonePane(
                ui,
                PairActions(onCancelConfirm = { ui = ui.copy(confirmHost = null) }, onBack = { left++ }),
            )
        }
        back()
        assertEquals(null, ui.confirmHost)
        assertEquals(0, left)
        back()
        assertEquals(1, left)
        assertStayed()
    }

    @Test
    @Config(qualifiers = "w475dp-h751dp-port-420dpi")
    fun wizardStepsBackThenClosesWhenOpenedFromTheApp() {
        val snapshot = Samples.snapshot()
        val previous = snapshot.previousStep(SetupStep.Enroll)
        assertNotNull(previous)
        var step by mutableStateOf(SetupStep.Enroll)
        var closed = 0
        show {
            SetupWizard(
                snapshot = snapshot,
                step = step,
                enroll = EnrollUiState.Idle,
                fold = FoldState.Cover,
                actions = SetupActions(onStepChange = { step = it }, onClose = { closed++ }),
                canClose = true,
            )
        }
        back()
        assertEquals(previous, step)
        while (snapshot.previousStep(step) != null) back()
        assertEquals(0, closed)
        back()
        assertEquals(1, closed)
        assertStayed()
    }

    @Test
    @Config(qualifiers = "w475dp-h751dp-port-420dpi")
    fun firstRunWizardLeavesTheAppFromItsFirstStep() {
        show {
            SetupWizard(
                snapshot = Samples.snapshot(),
                step = SetupStep.Welcome,
                enroll = EnrollUiState.Idle,
                fold = FoldState.Cover,
                actions = SetupActions(),
                canClose = false,
            )
        }
        assertExits()
    }

    @Test
    @Config(qualifiers = "w475dp-h751dp-port-420dpi")
    fun coverOpenMessageClosesBeforeMultiSelectThenMultiSelectEnds() {
        memory = ShellMemory()
        memory.inbox.picked = setOf(FakeInboxData.otp.id)
        memory.inbox.selectedId = FakeInboxData.otp.id
        showShell(FoldState.Cover)

        // The open message hides the list (and its 多选 bar): it closes first.
        back()
        assertEquals(null, memory.inbox.selectedId)
        assertEquals(setOf(FakeInboxData.otp.id), memory.inbox.picked)
        assertStayed()

        back()
        assertTrue(memory.inbox.picked.isEmpty())
        assertStayed()
        assertExits()
    }

    @Test
    @Config(qualifiers = "w475dp-h751dp-port-420dpi")
    fun coverMultiSelectFromTheMenuEndsOnBack() {
        memory = ShellMemory()
        memory.inbox.selectMode = true
        showShell(FoldState.Cover)

        back()
        assertFalse(memory.inbox.selectMode)
        assertStayed()
        assertExits()
    }

    @Test
    @Config(qualifiers = "w933dp-h704dp-land-420dpi")
    fun innerMultiSelectEndsBeforeTheAppExitsAndKeepsTheOpenMessage() {
        memory = ShellMemory()
        memory.inbox.picked = setOf(FakeInboxData.otp.id)
        memory.inbox.selectedId = FakeInboxData.otp.id
        showShell(FoldState.Flat)

        back()
        assertTrue(memory.inbox.picked.isEmpty())
        assertEquals(FakeInboxData.otp.id, memory.inbox.selectedId)
        assertStayed()
        assertExits()
    }

    @Test
    @Config(qualifiers = "w475dp-h751dp-port-420dpi")
    fun unpairedPhoneStartsOnForwardAndPairingReturnsToTheUnpairedInbox() {
        unpaired = true
        destination = Destination.Inbox
        memory = ShellMemory()
        memory.inbox.subScreen = InboxSubScreen.Pair
        showShell(FoldState.Cover, startDestination = Destination.Forward)

        back()
        assertEquals(InboxSubScreen.None, memory.inbox.subScreen)
        assertEquals(Destination.Inbox, destination)
        composeRule.onNodeWithTag("inbox_unpaired").assertExists()
        assertStayed()

        // 消息 is not the start tab here: back goes to 转发, and from there leaves the app.
        back()
        assertEquals(Destination.Forward, destination)
        assertStayed()

        composeRule.runOnUiThread { destination = Destination.Settings; memory.settingsItem = SettingsItem.Notifications }
        composeRule.waitForIdle()
        back()
        assertEquals(null, memory.settingsItem)
        back()
        assertEquals(Destination.Forward, destination)
        assertStayed()
        assertExits()
    }

    @Test
    @Config(qualifiers = "w704dp-h933dp-port-420dpi")
    fun tabletopAdminPageThenHomeThenListThenStartTab() {
        destination = Destination.Settings
        memory = ShellMemory(settingsItem = SettingsItem.Admin, adminPage = AdminPage.Sources)
        showShell(FoldState.tabletop())

        back()
        assertEquals(null, memory.adminPage)
        assertEquals(SettingsItem.Admin, memory.settingsItem)
        back()
        assertEquals(null, memory.settingsItem)
        back()
        assertEquals(Destination.Inbox, destination)
        assertStayed()
        assertExits()
    }

    /**
     * Tabletop 记录 and 消息 show the selection above the hinge and the list below it, both at
     * once (like two panes): the selection is part of the tab, not a level of its own.
     */
    @Test
    @Config(qualifiers = "w704dp-h933dp-port-420dpi")
    fun tabletopLogAndInboxKeepTheirSelectionLikeTwoPanes() {
        destination = Destination.Log
        memory = ShellMemory(selectedLogId = FakeForwarderData.log.first().id)
        memory.inbox.selectedId = FakeInboxData.otp.id
        showShell(FoldState.tabletop())
        composeRule.onNodeWithTag("log_tabletop").assertExists()

        back()
        assertEquals(Destination.Inbox, destination)
        assertEquals(FakeForwarderData.log.first().id, memory.selectedLogId)
        composeRule.onNodeWithTag("inbox_tabletop").assertExists()
        assertStayed()

        assertExits()
        assertEquals(FakeInboxData.otp.id, memory.inbox.selectedId)
    }

    @Test
    @Config(qualifiers = "w475dp-h751dp-port-420dpi")
    fun pairThisPhoneAsksBeforeHidingTheNumber() {
        var ui by mutableStateOf(PairUi(name = "Fold8", number = "42"))
        var left = 0
        show { PairThisPhonePane(ui, PairActions(onBack = { left++ })) }

        back()
        composeRule.onNodeWithTag("pair_leave_stay").assertExists().performClick()
        composeRule.waitForIdle()
        assertEquals(0, left)

        // The arrow asks too; 离开 then leaves.
        composeRule.onNodeWithTag("subscreen_back").performClick()
        composeRule.onNodeWithTag("pair_leave_confirm").assertExists().performClick()
        composeRule.waitForIdle()
        assertEquals(1, left)

        // Still connecting (no number yet) asks as well; with nothing in progress back just leaves.
        composeRule.runOnUiThread { ui = PairUi(name = "Fold8", connecting = true) }
        composeRule.waitForIdle()
        back()
        composeRule.onNodeWithTag("pair_leave_stay").assertExists().performClick()
        composeRule.waitForIdle()
        assertEquals(1, left)
        composeRule.runOnUiThread { ui = PairUi(name = "Fold8") }
        composeRule.waitForIdle()
        back()
        assertEquals(2, left)
        assertStayed()
    }

    /**
     * Without the probe: at the start tab's root no callback of the app is enabled, so the
     * system handles back itself (predictive back-to-home, then finish / move to the background).
     */
    @Test
    @Config(qualifiers = "w475dp-h751dp-port-420dpi")
    fun startTabRootHandsBackToTheSystem() {
        destination = Destination.Log
        composeRule.setContent {
            CompositionLocalProvider(LocalAppLock provides LockUiState(lock, 1)) {
                TestHarness {
                    NekopostShell(
                        state = state,
                        fold = FoldState.Cover,
                        destination = destination,
                        onDestinationChange = { destination = it },
                        memory = memory,
                        actions = ShellActions(),
                        startDestination = Destination.Forward,
                    )
                }
            }
        }
        composeRule.waitForIdle()
        val dispatcher = composeRule.activity.onBackPressedDispatcher
        assertTrue(dispatcher.hasEnabledCallbacks())
        back()
        assertEquals(Destination.Forward, destination)
        assertFalse(dispatcher.hasEnabledCallbacks())
        assertFalse(composeRule.activity.isFinishing)
    }
}
