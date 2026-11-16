package com.drinkcat.nekopost.ui

import android.app.Application
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import com.drinkcat.nekopost.core.forwarder.FailureEvent
import com.drinkcat.nekopost.core.forwarder.FailureReason
import com.drinkcat.nekopost.core.forwarder.FakeForwarderData
import com.drinkcat.nekopost.core.forwarder.OtpHealth
import com.drinkcat.nekopost.core.forwarder.PermissionStatus
import com.drinkcat.nekopost.core.forwarder.QueueStats
import com.drinkcat.nekopost.core.forwarder.SendTestResult
import com.drinkcat.nekopost.core.ui.fold.FoldState
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
            roborazziOptions = Options,
        ) {
            TestHarness(dark = case.dark) {
                case.screen.content(case.device.fold)
            }
        }
    }

    companion object {
        private val Options = RoborazziOptions(recordOptions = RoborazziOptions.RecordOptions(resizeScale = 0.5))

        private val enrolled = ShellUiState(
            forwarder = FakeForwarderData.enrolledState,
            log = FakeForwarderData.log,
            otp = FakeForwarderData.otpHealthy,
            appVersion = "0.1.0",
            apps = Samples.apps,
            smsRules = Samples.rules,
            debugPanelAvailable = true,
            foldDebug = Samples.foldDebug,
        )

        /** Backlog over the confirmation threshold, OTP delays, a missing permission, a queued test. */
        private val attention = enrolled.copy(
            forwarder = FakeForwarderData.enrolledState.copy(
                permissions = PermissionStatus.All.copy(notificationListener = false, ignoringBatteryOptimizations = false),
                queue = QueueStats(pending = 63, failed = 2, oldestPendingAt = FakeForwarderData.Now.minus(Duration.ofHours(3))),
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
        private fun Shell(state: ShellUiState, fold: FoldState, destination: Destination, memory: () -> ShellMemory = { ShellMemory() }) {
            NekopostShell(
                state = state,
                fold = fold,
                destination = destination,
                onDestinationChange = {},
                actions = ShellActions(),
                memory = remember { memory() },
            )
        }

        @Composable
        private fun Wizard(snapshot: SetupSnapshot, step: SetupStep, fold: FoldState, enroll: EnrollUiState = EnrollUiState.Idle) {
            SetupWizard(snapshot = snapshot, step = step, enroll = enroll, fold = fold, actions = SetupActions(), canClose = false)
        }

        private val sentTest = enrolled.copy(
            testSend = TestSendState.Done(SendTestResult.Accepted("m_01JB7QAB12CD34EF56GH78JK9M")),
        )

        val screens = listOf(
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
                Shell(enrolled, it, Destination.Settings) { ShellMemory(settingsItem = SettingsItem.About, debugUnlocked = true) }
            },
            Screen("settings_unenroll") { Shell(enrolled, it, Destination.Settings) { ShellMemory(settingsItem = SettingsItem.Unenroll) } },
            Screen("unenroll_sheet", devices = listOf(Fold8.Cover)) {
                Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxWidth()) {
                    UnenrollSheetContent(FakeForwarderData.enrollment, pending = 1, onConfirm = {}, onCancel = {})
                }
            },
            Screen("setup_welcome") { Wizard(Samples.snapshot(), SetupStep.Welcome, it) },
            Screen("setup_enroll") {
                Wizard(Samples.snapshot(), SetupStep.Enroll, it, enroll = EnrollUiState.Confirm(Samples.link))
            },
            Screen("setup_sms") { Wizard(Samples.snapshot(enrolled = true, smsBlocked = true), SetupStep.SmsPermission, it) },
            Screen("setup_battery") {
                Wizard(
                    Samples.snapshot(enrolled = true, permissions = PermissionStatus.All.copy(ignoringBatteryOptimizations = false)),
                    SetupStep.Battery,
                    it,
                )
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
