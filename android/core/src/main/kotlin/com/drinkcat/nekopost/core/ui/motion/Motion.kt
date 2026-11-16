package com.drinkcat.nekopost.core.ui.motion

import android.content.Context
import android.provider.Settings
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.runtime.staticCompositionLocalOf
import com.drinkcat.nekopost.core.ui.token.NekoTokens

/**
 * True when the system asks for reduced motion (developer option "Animator
 * duration scale" off, or the accessibility "Remove animations" switch).
 * Provided once at the top of the UI (MainActivity) and in screenshot tests,
 * where it is always true so captures are deterministic.
 */
val LocalReducedMotion = staticCompositionLocalOf { false }

/** Reads the system "remove animations" state (animator duration scale == 0). */
fun Context.isReducedMotionEnabled(): Boolean =
    Settings.Global.getFloat(contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f

/** Motion specs of design §9.5: springs normally, a short fade (or nothing) when reduced. */
object NekoMotion {
    /** Spatial movement (position, size, scale). */
    fun <T> spatial(reduced: Boolean): FiniteAnimationSpec<T> =
        if (reduced) snap() else spring(dampingRatio = 0.72f, stiffness = Spring.StiffnessMediumLow)

    /** Colour and opacity changes. */
    fun <T> effects(reduced: Boolean): AnimationSpec<T> =
        if (reduced) tween(NekoTokens.Motion.REDUCED_MS) else spring(stiffness = Spring.StiffnessMediumLow)
}
