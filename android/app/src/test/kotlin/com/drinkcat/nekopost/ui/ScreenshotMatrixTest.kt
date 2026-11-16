package com.drinkcat.nekopost.ui

import android.app.Application
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import com.drinkcat.nekopost.core.enroll.EnrollmentError
import com.drinkcat.nekopost.core.forwarder.FailureEvent
import com.drinkcat.nekopost.core.forwarder.FailureReason
import com.drinkcat.nekopost.core.forwarder.FakeForwarderData
import com.drinkcat.nekopost.core.forwarder.OtpHealth
import com.drinkcat.nekopost.core.forwarder.PermissionStatus
import com.drinkcat.nekopost.core.forwarder.QueueStats
import com.drinkcat.nekopost.core.forwarder.SendTestResult
import com.drinkcat.nekopost.core.forwarder.SmsBacklog
import com.drinkcat.nekopost.core.ui.fold.FoldState
import com.drinkcat.nekopost.inbox.DropNoteContent
import com.drinkcat.nekopost.inbox.FakeInboxData
import com.drinkcat.nekopost.inbox.PairThisPhonePane
import com.drinkcat.nekopost.inbox.PairUi
import com.drinkcat.nekopost.inbox.PairActions
import com.drinkcat.nekopost.inbox.SortingDeskContent
import com.drinkcat.nekopost.inbox.SortingUi
import com.drinkcat.nekopost.inbox.WatchDeskContent
import com.drinkcat.nekopost.inbox.WatchUi
import com.drinkcat.nekopost.inbox.ui.InboxActions
import com.drinkcat.nekopost.inbox.ui.InboxFilter
import com.drinkcat.nekopost.inbox.ui.InboxScreen
import com.drinkcat.nekopost.inbox.ui.InboxUiState
import com.drinkcat.nekopost.inbox.ui.InboxUnpaired
import com.drinkcat.nekopost.lock.LockIssue
import com.drinkcat.nekopost.lock.LockReason
import com.drinkcat.nekopost.lock.LockScreen
import com.drinkcat.nekopost.lock.LockState
import com.drinkcat.nekopost.ui.settings.AdminPage
import com.drinkcat.nekopost.ui.settings.LockSettingsUi
import com.drinkcat.nekopost.ui.settings.NotifySettingsUi
import com.drinkcat.nekopost.ui.shell.InboxMemory
import com.drinkcat.nekopost.ui.shell.PaneMode
import com.drinkcat.nekopost.ui.shell.paneModeFor
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import com.drinkcat.nekopost.ui.settings.SettingsItem
import com.drinkcat.nekopost.ui.settings.UnenrollSheetContent
import com.drinkcat.nekopost.ui.setup.EnrollUiState
import com.drinkcat.nekopost.ui.setup.SetupActions
import com.drinkcat.nekopost.ui.setup.SetupSnapshot
import com.drinkcat.nekopost.ui.setup.SetupStep
import com.drinkcat.nekopost.ui.setup.SetupWizard
import com.drinkcat.nekopost.ui.shell.Destination
import com.drinkcat.nekopost.ui.shell.NekopostShell
import com.drinkcat.nekopost.ui.shell.ShellActions
import com.drinkcat.nekopost.ui.shell.ShellMemory
import com.drinkcat.nekopost.ui.shell.ShellUiState
import com.drinkcat.nekopost.ui.shell.TestSendState
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.Duration

/**
 * Screenshot matrix on the Galaxy Z Fold8 (wide model): every screen ×
 * {cover, inner landscape (flat), inner portrait tabletop} × {light, dark}.
 * The posture is injected as a FoldState, so tabletop renders without a real
 * FoldingFeature.
 *
 * Plain `testDebugUnitTest` skips the captures; `recordRoborazziDebug` writes
 * the reference images to app/src/test/screenshots and `verifyRoborazziDebug`
 * compares against them. Images are stored at half resolution to keep the
 * repository small.
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], application = Application::class)
class ScreenshotMatrixTest(private val case: Case) {

    /** One screen in one state; [content] receives the injected posture. */
    class Screen(val name: String, val devices: List<Device> = Fold8.All, val content: @Composable (FoldState) -> Unit)

    data class Case(val device: Device, val dark: Boolean, val screen: Screen) {
        val fileName: String get() = "${device.name}_${screen.name}_${if (dark) "dark" else "light"}"
        override fun toString() = fileName
    }

    @Test
    fun capture() {
        RuntimeEnvironment.setQualifiers(case.device.qualifiers)
        captureRoboImage(
            filePath = "src/test/screenshots/${case.fileName}.png",
            // Built here, inside the Robolectric sandbox: its defaults read the Roborazzi context.
            roborazziOptions = RoborazziOptions(recordOptions = RoborazziOptions.RecordOptions(resizeScale = 0.5)),
        ) {
            TestHarness(dark = case.dark) {
                case.screen.content(case.device.fold)
            }
        }
    }

    companion object {
        private val enrolled = ShellUiState(
            forwarder = FakeForwarderData.enrolledState,
            log = FakeForwarderData.log,
            otp = FakeForwarderData.otpHealthy,
            appVersion = "0.1.0",
            apps = Samples.apps,
            smsRules = Samples.rules,
            debugPanelAvailable = true,
        )

        /** SMS backlog held for confirmation, OTP delays, a missing permission, a queued test. */
        private val attention = enrolled.copy(
            forwarder = FakeForwarderData.enrolledState.copy(
                permissions = PermissionStatus.All.copy(notificationListener = false, ignoringBatteryOptimizations = false),
                queue = QueueStats(pending = 63, failed = 2, oldestPendingAt = FakeForwarderData.Now.minus(Duration.ofHours(3))),
                smsBacklog = SmsBacklog(63, oldestAt = FakeForwarderData.Now.minus(Duration.ofHours(30)), detectedAt = FakeForwarderData.Now),
                lastFailure = FailureEvent(FakeForwarderData.Now.minusSeconds(40), FailureReason.RateLimited, httpStatus = 429),
            ),
            otp = FakeForwarderData.otpDelayed,
            setupPending = 2,
            testSend = TestSendState.Done(SendTestResult.Queued(FailureReason.Network)),
        )

        private val unenrolled = ShellUiState(
            forwarder = FakeForwarderData.notEnrolledState,
            log = emptyList(),
            otp = OtpHealth.Empty,
            appVersion = "0.1.0",
            setupPending = 6,
        )

        @Composable
        private fun Shell(
            state: ShellUiState,
            fold: FoldState,
            destination: Destination,
            inbox: InboxUiState? = null,
            memory: () -> ShellMemory = { ShellMemory() },
        ) {
            NekopostShell(
                state = state,
                fold = fold,
                destination = destination,
                onDestinationChange = {},
                actions = ShellActions(),
                memory = remember { memory() },
                // A phone that can read always has 消息 (first), as in the app.
                inboxContent = (inbox ?: FakeInboxData.list.takeIf { state.device?.canRead == true })
                    ?.let { ui -> @Composable { mode: PaneMode -> InboxScreen(ui, fold, mode, InboxActions()) } },
            )
        }

        /** The inbox alone, in the pane mode the shell would give it (no navigation bar). */
        @Composable
        private fun Inbox(ui: InboxUiState, fold: FoldState) {
            Surface(color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxSize()) {
                BoxWithConstraints {
                    InboxScreen(ui, fold, paneModeFor(fold, maxWidth), InboxActions())
                }
            }
        }

        @Composable
        private fun Plain(content: @Composable () -> Unit) {
            Surface(color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxSize()) { content() }
        }

        private val device = Samples.thisDevice
        private val reader = enrolled.copy(device = device, inboxUnread = 4, lock = LockSettingsUi(foldable = true))
        private val admin = reader.copy(admin = Samples.admin)

        @Composable
        private fun Wizard(snapshot: SetupSnapshot, step: SetupStep, fold: FoldState, enroll: EnrollUiState = EnrollUiState.Idle) {
            SetupWizard(snapshot = snapshot, step = step, enroll = enroll, fold = fold, actions = SetupActions(), canClose = false)
        }

        private val sentTest = enrolled.copy(
            testSend = TestSendState.Done(SendTestResult.Accepted("m_01JB7QAB12CD34EF56GH78JK9M")),
        )

        private val cover = listOf(Fold8.Cover)
        private val coverFlat = listOf(Fold8.Cover, Fold8.InnerFlat)

        val screens = listOf(
            // 消息 (requirement 3): a messaging app's list and detail.
            Screen("shell_inbox") { Shell(reader, it, Destination.Inbox, inbox = FakeInboxData.list) },
            Screen("inbox_detail_otp") { Shell(reader, it, Destination.Inbox, inbox = FakeInboxData.withDetail(FakeInboxData.otpDetail)) },
            Screen("inbox_detail_expired", cover) { Inbox(FakeInboxData.withDetail(FakeInboxData.expired), it) },
            Screen("inbox_empty", coverFlat) { Inbox(InboxUiState(), it) },
            Screen("inbox_empty_notifications_off", cover) { Inbox(InboxUiState(notificationsBlocked = true), it) },
            Screen("inbox_loading", cover) { Inbox(InboxUiState(loading = true), it) },
            Screen("inbox_offline", cover) {
                Inbox(FakeInboxData.list.copy(offlineSince = FakeForwarderData.Now.minus(Duration.ofHours(3))), it)
            },
            Screen("inbox_error", cover) { Inbox(InboxUiState(error = "连不上服务器，请检查网络。"), it) },
            Screen("inbox_search", coverFlat) { Inbox(FakeInboxData.list.copy(query = "验证码", searchedQuery = "验证码", rows = listOf(FakeInboxData.otp)), it) },
            Screen("inbox_search_none", cover) { Inbox(InboxUiState(query = "发票", searchedQuery = "发票"), it) },
            Screen("inbox_filter_codes", cover) { Inbox(FakeInboxData.list.copy(filter = InboxFilter.Codes), it) },
            Screen("inbox_unpaired", coverFlat) { Plain { InboxUnpaired(revoked = false, onPair = {}) } },
            Screen("pair_entry", cover) { Plain { PairThisPhonePane(PairUi(name = "Galaxy Z Fold8", server = "https://osaka.drinkcat.com/"), PairActions(onBack = {})) } },
            Screen("pair_number", cover) { Plain { PairThisPhonePane(PairUi(name = "Galaxy Z Fold8", number = "27"), PairActions(onBack = {})) } },
            // 应用锁 (requirement 1).
            Screen("lock_locked") { LockScreen(LockState.Locked(LockReason.ColdStart), it, issue = null, onUnlock = {}) },
            Screen("lock_lockout", cover) { LockScreen(LockState.Locked(LockReason.Idle), it, issue = LockIssue.Lockout, onUnlock = {}) },
            Screen("lock_curtain", cover) { LockScreen(LockState.Curtain, it, issue = null, onUnlock = {}) },
            // Fold tools, moved out of the main flow.
            Screen("watch_desk", listOf(Fold8.InnerTabletop)) {
                Plain { WatchDeskContent(WatchUi(FakeInboxData.rows.take(4), connected = true), it, {}, {}, {}, {}) }
            },
            Screen("sorting_desk", listOf(Fold8.InnerTabletop, Fold8.InnerFlat)) {
                Plain { SortingDeskContent(SortingUi(FakeInboxData.long.copy(body = FakeInboxData.long.preview)), it, {}, {}, {}, {}, {}) }
            },
            Screen("drop_note", cover) {
                Plain { DropNoteContent("记得周五前续费域名", {}, dragEnabled = false, onDragEnabledChange = {}, message = null, pending = false, onSend = {}, onBack = {}) }
            },
            Screen("forward") { Shell(sentTest, it, Destination.Forward) },
            Screen("forward_attention") { Shell(attention, it, Destination.Forward) },
            Screen("forward_unenrolled") { Shell(unenrolled, it, Destination.Forward) },
            Screen("log") { Shell(enrolled, it, Destination.Log) },
            Screen("log_selected") { Shell(enrolled, it, Destination.Log) { ShellMemory(selectedLogId = 6) } },
            Screen("log_empty") { Shell(unenrolled, it, Destination.Log) },
            Screen("settings") { Shell(enrolled, it, Destination.Settings) },
            Screen("settings_apps") { Shell(enrolled, it, Destination.Settings) { ShellMemory(settingsItem = SettingsItem.Apps) } },
            Screen("settings_rules") { Shell(enrolled, it, Destination.Settings) { ShellMemory(settingsItem = SettingsItem.SmsRules) } },
            Screen("settings_about") {
                Shell(enrolled.copy(foldDebug = Samples.foldDebug(it)), it, Destination.Settings) {
                    ShellMemory(settingsItem = SettingsItem.About, debugUnlocked = true)
                }
            },
            Screen("unenroll_sheet", devices = listOf(Fold8.Cover)) {
                Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxWidth()) {
                    UnenrollSheetContent(FakeForwarderData.enrollment, device = null, pending = 1, onConfirm = {}, onCancel = {})
                }
            },
            Screen("unenroll_sheet_both", devices = listOf(Fold8.Cover)) {
                Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxWidth()) {
                    UnenrollSheetContent(FakeForwarderData.enrollment, device = device, pending = 0, onConfirm = {}, onCancel = {})
                }
            },
            Screen("settings_reader") { Shell(admin, it, Destination.Settings) },
            Screen("settings_account", cover) { Shell(admin, it, Destination.Settings) { ShellMemory(settingsItem = SettingsItem.Account) } },
            Screen("settings_lock", coverFlat) { Shell(reader, it, Destination.Settings) { ShellMemory(settingsItem = SettingsItem.AppLock) } },
            Screen("settings_lock_insecure", cover) {
                Shell(reader.copy(lock = LockSettingsUi(enabled = true, secure = false)), it, Destination.Settings) { ShellMemory(settingsItem = SettingsItem.AppLock) }
            },
            Screen("settings_notifications", coverFlat) { Shell(admin, it, Destination.Settings) { ShellMemory(settingsItem = SettingsItem.Notifications) } },
            Screen("settings_notifications_unpaired", cover) {
                Shell(enrolled.copy(notify = NotifySettingsUi(permission = false)), it, Destination.Settings) { ShellMemory(settingsItem = SettingsItem.Notifications) }
            },
            Screen("settings_admin", cover) { Shell(admin, it, Destination.Settings) { ShellMemory(settingsItem = SettingsItem.Admin) } },
            Screen("settings_devices", coverFlat) {
                Shell(admin, it, Destination.Settings) { ShellMemory(settingsItem = SettingsItem.Admin, adminPage = AdminPage.Devices) }
            },
            Screen("settings_add_device", cover) {
                Shell(admin, it, Destination.Settings) { ShellMemory(settingsItem = SettingsItem.Admin, adminPage = AdminPage.AddDevice) }
            },
            Screen("settings_pairing_claimed", listOf(Fold8.Cover, Fold8.InnerTabletop)) {
                Shell(admin.copy(admin = Samples.admin.copy(pairing = Samples.pairingClaimed)), it, Destination.Settings) {
                    ShellMemory(settingsItem = SettingsItem.Admin, adminPage = AdminPage.AddDevice)
                }
            },
            Screen("settings_pairing_open", cover) {
                Shell(admin.copy(admin = Samples.admin.copy(pairing = Samples.pairingOpen)), it, Destination.Settings) {
                    ShellMemory(settingsItem = SettingsItem.Admin, adminPage = AdminPage.AddDevice)
                }
            },
            Screen("setup_welcome") { Wizard(Samples.snapshot(), SetupStep.Welcome, it) },
            Screen("setup_enroll") {
                Wizard(Samples.snapshot(), SetupStep.Enroll, it, enroll = EnrollUiState.Confirm(Samples.link))
            },
            Screen("setup_enroll_invalid") {
                Wizard(Samples.snapshot(), SetupStep.Enroll, it, enroll = EnrollUiState.Invalid(EnrollmentError.InsecureServerUrl))
            },
            Screen("setup_restricted") {
                Wizard(Samples.snapshot(enrolled = true, smsBlocked = true), SetupStep.RestrictedSettings, it)
            },
            Screen("setup_sms") { Wizard(Samples.snapshot(enrolled = true, smsBlocked = true), SetupStep.SmsPermission, it) },
            Screen("setup_listener") {
                Wizard(
                    Samples.snapshot(enrolled = true, permissions = PermissionStatus.None.copy(receiveSms = true, readSms = true)),
                    SetupStep.NotificationAccess,
                    it,
                )
            },
            Screen("setup_post") {
                Wizard(
                    Samples.snapshot(enrolled = true, permissions = PermissionStatus.All.copy(postNotifications = false)),
                    SetupStep.PostNotifications,
                    it,
                )
            },
            Screen("setup_battery") {
                Wizard(
                    Samples.snapshot(enrolled = true, permissions = PermissionStatus.All.copy(ignoringBatteryOptimizations = false)),
                    SetupStep.Battery,
                    it,
                )
            },
            Screen("setup_cover") {
                Wizard(Samples.snapshot(enrolled = true, permissions = PermissionStatus.All), SetupStep.CoverScreen, it)
            },
            Screen("setup_otp") {
                Wizard(
                    Samples.snapshot(enrolled = true, permissions = PermissionStatus.All, otp = FakeForwarderData.otpHealthy),
                    SetupStep.OtpSelfTest,
                    it,
                )
            },
            Screen("setup_finish") {
                Wizard(
                    Samples.snapshot(enrolled = true, permissions = PermissionStatus.All, otp = FakeForwarderData.otpHealthy),
                    SetupStep.Finish,
                    it,
                )
            },
        )

        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun cases(): List<Array<Any>> {
            val cases = mutableListOf<Case>()
            for (screen in screens) {
                for (device in screen.devices) {
                    for (dark in listOf(false, true)) {
                        cases += Case(device, dark, screen)
                    }
                }
            }
            return cases.map { arrayOf<Any>(it) }
        }
    }
}
