package com.drinkcat.nekopost.ui

import android.app.Application
import androidx.compose.runtime.CompositionLocalProvider
import com.drinkcat.nekopost.core.forwarder.FakeForwarderData
import com.drinkcat.nekopost.core.ui.fold.FoldState
import com.drinkcat.nekopost.core.ui.theme.NekopostTheme
import com.drinkcat.nekopost.ui.shell.Destination
import com.drinkcat.nekopost.ui.shell.NekopostShell
import com.drinkcat.nekopost.ui.shell.ShellActions
import com.drinkcat.nekopost.ui.shell.ShellUiState
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.ZoneId

/**
 * Screenshot matrix for the adaptive shell on the Galaxy Z Fold8 (wide model):
 * {cover, inner landscape flat, inner portrait tabletop} x {light, dark} x
 * every destination. The posture is injected as a FoldState, so tabletop is
 * rendered without a real FoldingFeature.
 *
 * Plain `testDebugUnitTest` skips the captures; `recordRoborazziDebug` writes
 * the reference images to app/src/test/screenshots and `verifyRoborazziDebug`
 * compares against them.
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], application = Application::class)
class ShellScreenshotTest(private val case: Case) {

    data class Device(val name: String, val qualifiers: String, val fold: FoldState)

    data class Case(val device: Device, val dark: Boolean, val destination: Destination, val enrolled: Boolean = true) {
        val fileName: String
            get() = buildString {
                append(device.name)
                append('_')
                append(destination.name.lowercase())
                if (!enrolled) append("_unenrolled")
                append(if (dark) "_dark" else "_light")
            }

        override fun toString() = fileName
    }

    @Test
    fun capture() {
        RuntimeEnvironment.setQualifiers(case.device.qualifiers)
        val forwarder = if (case.enrolled) FakeForwarderData.enrolledState else FakeForwarderData.notEnrolledState
        val state = ShellUiState(
            forwarder = forwarder,
            log = if (case.enrolled) FakeForwarderData.log else emptyList(),
            otp = FakeForwarderData.otpHealthy,
            appVersion = "0.1.0",
        )
        captureRoboImage(filePath = "src/test/screenshots/${case.fileName}.png") {
            CompositionLocalProvider(LocalDisplayZone provides ZoneId.of("Asia/Tokyo")) {
                NekopostTheme(darkTheme = case.dark) {
                    NekopostShell(
                        state = state,
                        fold = case.device.fold,
                        destination = case.destination,
                        onDestinationChange = {},
                        actions = ShellActions(),
                    )
                }
            }
        }
    }

    companion object {
        // Fold8 (wide) at the default 420 dpi; see design §10.10.
        val Cover = Device("cover", "w475dp-h751dp-port-420dpi", FoldState.Cover)
        val InnerFlat = Device("inner_flat", "w933dp-h704dp-land-420dpi", FoldState.Flat)
        val InnerTabletop = Device("inner_tabletop", "w704dp-h933dp-port-420dpi", FoldState.tabletop())

        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun cases(): List<Array<Any>> {
            val cases = mutableListOf<Case>()
            for (device in listOf(Cover, InnerFlat, InnerTabletop)) {
                for (dark in listOf(false, true)) {
                    for (destination in Destination.entries) {
                        cases += Case(device, dark, destination)
                    }
                }
            }
            // First-run state on the cover display.
            cases += Case(Cover, dark = false, destination = Destination.Forward, enrolled = false)
            cases += Case(Cover, dark = true, destination = Destination.Log, enrolled = false)
            return cases.map { arrayOf<Any>(it) }
        }
    }
}
