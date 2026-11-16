package com.drinkcat.nekopost.lock

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import com.drinkcat.nekopost.core.ui.fold.FoldState
import com.drinkcat.nekopost.ui.RegisterComponentActivityRule
import com.drinkcat.nekopost.ui.TestHarness
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The lock hides the app without disposing it: typed text and screen state survive. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class LockGateTest {
    private val composeRule = createAndroidComposeRule<ComponentActivity>()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(RegisterComponentActivityRule()).around(composeRule)

    private var lock by mutableStateOf<LockState>(LockState.Unlocked)
    private var unlockTaps = 0

    private fun show() {
        composeRule.setContent {
            TestHarness {
                LockGate(lock, FoldState.Cover, issue = null, onUnlock = { unlockTaps++ }) {
                    Column {
                        // Plain remember: survives only if the content is never disposed.
                        var text by remember { mutableStateOf("") }
                        TextField(text, { text = it }, modifier = Modifier.testTag("field"))
                        Text("招商银行 验证码 482913")
                    }
                }
            }
        }
    }

    @Test
    fun contentSurvivesLockAndUnlock() {
        show()
        composeRule.onNodeWithTag("field").performTextInput("PAIR-1234")
        lock = LockState.Locked(LockReason.Background)
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("lock_screen").assertIsDisplayed()
        lock = LockState.Unlocked
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("field").assertTextContains("PAIR-1234")
    }

    @Test
    fun lockedContentHasNoSemantics() {
        show()
        composeRule.onNodeWithText("招商银行 验证码 482913").assertExists()
        lock = LockState.Locked(LockReason.Idle)
        composeRule.waitForIdle()
        composeRule.onNodeWithText("招商银行 验证码 482913").assertDoesNotExist()
        composeRule.onNodeWithText("招商银行 验证码 482913", useUnmergedTree = true).assertDoesNotExist()
        composeRule.onNodeWithTag("field").assertDoesNotExist()
    }

    @Test
    fun unlockButtonAndCurtainTap() {
        show()
        lock = LockState.Locked(LockReason.ColdStart)
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("lock_unlock").performClick()
        assertEquals(1, unlockTaps)
        lock = LockState.Curtain
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("lock_curtain").performClick()
        assertEquals(2, unlockTaps)
    }

    @Test
    fun unavailableNeverHidesTheApp() {
        lock = LockState.Unavailable
        show()
        composeRule.onNodeWithTag("field").assertIsDisplayed()
        composeRule.onNodeWithTag("lock_screen").assertDoesNotExist()
    }
}
