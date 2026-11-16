package com.drinkcat.nekopost.system

import android.view.MotionEvent
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.drinkcat.nekopost.R

/**
 * Tapjacking defence (design §8.3: "审批、批准配对、轮换凭证、吊销等按钮设置
 * filterTouchesWhenObscured"). Compose has no per-button touch filter, so
 * MainActivity feeds every touch event here and the confirm buttons ask
 * whether the gesture that triggered them passed through another app's window.
 */
object TouchSecurity {
    private const val OBSCURED = MotionEvent.FLAG_WINDOW_IS_OBSCURED or MotionEvent.FLAG_WINDOW_IS_PARTIALLY_OBSCURED

    @Volatile
    private var obscured = false

    /** Called by MainActivity.dispatchTouchEvent for every event, before dispatching it. */
    fun record(event: MotionEvent) {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) obscured = false
        if (event.flags and OBSCURED != 0) obscured = true
    }

    /** Whether any event of the latest gesture (down to now) came through an overlay. */
    val lastGestureObscured: Boolean get() = obscured
}

/**
 * Wraps sensitive actions: the action runs only if the gesture that
 * triggered it was not obscured by another app's window; otherwise a short
 * message explains why nothing happened.
 */
@Composable
fun rememberObscuredTouchGuard(): (() -> Unit) -> (() -> Unit) {
    val context = LocalContext.current
    return remember<(() -> Unit) -> (() -> Unit)>(context) {
        { action ->
            {
                if (TouchSecurity.lastGestureObscured) {
                    Toast.makeText(context, R.string.touch_obscured, Toast.LENGTH_LONG).show()
                } else {
                    action()
                }
            }
        }
    }
}
