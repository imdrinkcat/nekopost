package com.drinkcat.nekopost.system

import android.app.Application
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class TouchSecurityTest {
    private fun event(action: Int, flags: Int = 0): MotionEvent {
        val now = SystemClock.uptimeMillis()
        val props = arrayOf(MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_FINGER })
        val coords = arrayOf(MotionEvent.PointerCoords().apply { x = 10f; y = 10f })
        return MotionEvent.obtain(now, now, action, 1, props, coords, 0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, flags)
    }

    @Test
    fun gestureThroughAnOverlayIsFlaggedUntilTheNextDown() {
        TouchSecurity.record(event(MotionEvent.ACTION_DOWN))
        TouchSecurity.record(event(MotionEvent.ACTION_UP))
        assertFalse(TouchSecurity.lastGestureObscured)

        TouchSecurity.record(event(MotionEvent.ACTION_DOWN, MotionEvent.FLAG_WINDOW_IS_OBSCURED))
        TouchSecurity.record(event(MotionEvent.ACTION_UP))
        assertTrue(TouchSecurity.lastGestureObscured)

        // Partially obscured on any event of the gesture counts too.
        TouchSecurity.record(event(MotionEvent.ACTION_DOWN))
        TouchSecurity.record(event(MotionEvent.ACTION_UP, MotionEvent.FLAG_WINDOW_IS_PARTIALLY_OBSCURED))
        assertTrue(TouchSecurity.lastGestureObscured)

        TouchSecurity.record(event(MotionEvent.ACTION_DOWN))
        assertFalse(TouchSecurity.lastGestureObscured)
    }
}
