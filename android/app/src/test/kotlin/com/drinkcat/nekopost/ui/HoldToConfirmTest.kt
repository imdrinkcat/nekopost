package com.drinkcat.nekopost.ui

import android.app.Application
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import com.drinkcat.nekopost.core.ui.component.HoldToConfirmButton
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.rules.RuleChain
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The unenroll confirmation must need a real 1.5 s hold, even with animations turned off. */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class HoldToConfirmTest {
    /** "Remove animations": the system animator duration scale is 0. */
    private val rule = createComposeRule(
        effectContext = object : MotionDurationScale {
            override val scaleFactor: Float get() = 0f
        },
    )

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(RegisterComponentActivityRule()).around(rule)

    @Test
    fun tapDoesNotConfirmWithAnimationsOff() {
        var confirmed = 0
        rule.setContent {
            HoldToConfirmButton(text = "解除绑定", onConfirm = { confirmed++ }, modifier = Modifier.testTag("hold"))
        }
        rule.mainClock.autoAdvance = false

        // A short tap.
        rule.onNodeWithTag("hold").performTouchInput { down(center) }
        rule.mainClock.advanceTimeBy(100)
        rule.onNodeWithTag("hold").performTouchInput { up() }
        rule.mainClock.advanceTimeBy(2_000)
        assertEquals(0, confirmed)

        // A real hold confirms once 1.5 s have passed.
        rule.onNodeWithTag("hold").performTouchInput { down(center) }
        rule.mainClock.advanceTimeBy(800)
        assertEquals(0, confirmed)
        rule.mainClock.advanceTimeBy(900)
        assertEquals(1, confirmed)
        rule.onNodeWithTag("hold").performTouchInput { up() }
    }
}
