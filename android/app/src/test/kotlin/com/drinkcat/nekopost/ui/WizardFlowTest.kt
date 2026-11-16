package com.drinkcat.nekopost.ui

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import com.drinkcat.nekopost.core.forwarder.FakeForwarderController
import com.drinkcat.nekopost.core.forwarder.FakeForwarderData
import com.drinkcat.nekopost.core.forwarder.OtpHealth
import com.drinkcat.nekopost.core.forwarder.PermissionStatus
import com.drinkcat.nekopost.core.ui.fold.FoldState
import com.drinkcat.nekopost.ui.setup.SetupRoute
import com.drinkcat.nekopost.ui.setup.SetupSnapshot
import com.drinkcat.nekopost.ui.setup.SetupStep
import com.drinkcat.nekopost.ui.setup.StepStatus
import com.drinkcat.nekopost.ui.setup.maskToken
import com.drinkcat.nekopost.ui.setup.nextStep
import com.drinkcat.nekopost.ui.setup.pendingRequired
import com.drinkcat.nekopost.ui.setup.statusOf
import com.drinkcat.nekopost.ui.setup.visibleSteps
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Wizard state transitions through the real [SetupRoute] with the fake
 * forwarder: welcome → enroll (invalid, then valid link, confirm) → live
 * permission checks flipping from ⚠️ to ✅ → finish.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class, qualifiers = "w475dp-h751dp-port-420dpi")
class WizardFlowTest {
    private val composeRule = createAndroidComposeRule<ComponentActivity>()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(RegisterComponentActivityRule()).around(composeRule)

    private val controller = FakeForwarderController(initial = FakeForwarderData.notEnrolledState, log = emptyList(), otp = OtpHealth.Empty)
    private var permissions by mutableStateOf(PermissionStatus.None)
    private var step by mutableStateOf(SetupStep.Welcome)
    private var acknowledged by mutableStateOf(emptySet<SetupStep>())
    private var finished = false

    @Composable
    private fun Harness() {
        val f by controller.state.collectAsState()
        TestHarness {
            SetupRoute(
                controller = controller,
                snapshot = SetupSnapshot(
                    enrollment = f.enrollment,
                    permissions = permissions,
                    otp = OtpHealth.Empty,
                    device = Samples.device,
                    acknowledged = acknowledged,
                ),
                step = step,
                fold = FoldState.Cover,
                canClose = false,
                allowInsecureLocalhost = false,
                onStepChange = { step = it },
                onAcknowledge = { acknowledged = acknowledged + it },
                onSmsBlockedChange = {},
                onRefreshChecks = {},
                onFinish = { finished = true },
                onClose = {},
            )
        }
    }

    @Test
    fun welcomeThenEnrollWithPastedLink() {
        composeRule.setContent { Harness() }
        composeRule.onNodeWithText("欢迎来到猫邮局").assertIsDisplayed()
        composeRule.onNodeWithTag("setup_next").performClick()
        composeRule.waitForIdle()
        assertEquals(SetupStep.Enroll, step)
        assertTrue(SetupStep.Welcome in acknowledged)
        composeRule.onNodeWithText("连接你的服务器").assertIsDisplayed()

        // Not an enrollment link: rejected with an explanation, nothing sent.
        composeRule.onNodeWithTag("enroll_paste_field").performTextInput("https://example.com/")
        composeRule.onNodeWithTag("enroll_check").performClick()
        composeRule.onNodeWithText("这不是猫邮局的配对二维码。").assertIsDisplayed()
        assertFalse(controller.state.value.isEnrolled)

        // A valid link: confirmation card first, enrollment only after confirming.
        composeRule.onNodeWithTag("enroll_paste_field").performTextClearance()
        composeRule.onNodeWithTag("enroll_paste_field").performTextInput(Samples.link.toUriString())
        composeRule.onNodeWithTag("enroll_check").performClick()
        composeRule.onNodeWithText("osaka.drinkcat.com").assertIsDisplayed()
        composeRule.onNodeWithText(maskToken(Samples.link.token)).assertIsDisplayed()
        assertFalse(controller.state.value.isEnrolled)

        composeRule.onNodeWithTag("enroll_confirm").performClick()
        composeRule.waitForIdle()
        assertTrue(controller.state.value.isEnrolled)
        composeRule.onNodeWithText("已配对「Fold8 主力机」").assertIsDisplayed()
        // The step is now done, so "next" is the filled button again.
        composeRule.onNodeWithText("下一步").assertIsDisplayed()
    }

    @Test
    fun permissionStepFlipsWhenRecheckedOnResume() {
        step = SetupStep.SmsPermission
        composeRule.setContent { Harness() }
        composeRule.onAllNodesWithText("需要处理")[0].assertIsDisplayed()
        composeRule.onNodeWithText("稍后再说").assertIsDisplayed()

        // What onResume does after the user granted the permission in the system dialog.
        permissions = PermissionStatus.None.copy(receiveSms = true, readSms = true)
        composeRule.waitForIdle()
        composeRule.onAllNodesWithText("已完成")[0].assertIsDisplayed()
        composeRule.onNodeWithText("下一步").assertIsDisplayed()
    }

    @Test
    fun finishCallsBack() {
        permissions = PermissionStatus.All
        step = SetupStep.Finish
        composeRule.setContent { Harness() }
        composeRule.onNodeWithTag("setup_finish").performClick()
        assertTrue(finished)
    }

    @Test
    fun stepLogic() {
        val fresh = Samples.snapshot()
        assertEquals(StepStatus.NeedsAction, fresh.statusOf(SetupStep.Enroll))
        assertEquals(6, fresh.pendingRequired().size)
        assertEquals(SetupStep.Enroll, fresh.nextStep(SetupStep.Welcome))

        val ready = Samples.snapshot(enrolled = true, permissions = PermissionStatus.All)
        assertTrue(ready.pendingRequired().isEmpty())
        assertEquals(StepStatus.Done, ready.statusOf(SetupStep.RestrictedSettings))

        // Restricted settings do not exist before Android 13; cover-screen guidance only on foldables.
        val old = fresh.copy(device = fresh.device.copy(sdkInt = 31, isFoldable = false))
        assertFalse(SetupStep.RestrictedSettings in old.visibleSteps())
        assertFalse(SetupStep.CoverScreen in old.visibleSteps())
        assertEquals(SetupStep.SmsPermission, old.nextStep(SetupStep.Enroll))

        // A refused SMS request overrides a manual "done" on the restricted-settings step.
        val blocked = fresh.copy(acknowledged = setOf(SetupStep.RestrictedSettings), smsBlocked = true)
        assertEquals(StepStatus.NeedsAction, blocked.statusOf(SetupStep.RestrictedSettings))
    }

    @Test
    fun tokenIsMasked() {
        val masked = maskToken(Samples.link.token)
        assertTrue(masked.startsWith("mgs_"))
        assertFalse(masked.contains(Samples.link.token.substring(8, Samples.link.token.length - 4)))
    }
}
