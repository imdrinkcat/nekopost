package com.drinkcat.nekopost.core.ui.fold

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.toComposeRect
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.window.core.layout.WindowSizeClass
import androidx.window.layout.FoldingFeature
import androidx.window.layout.WindowInfoTracker
import com.drinkcat.nekopost.core.ui.token.NekoTokens
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

/**
 * Computes the current [FoldState] from WindowInfoTracker and the window size
 * class. Call it once at the top of the UI and pass the result down; screens
 * must not call it themselves.
 *
 * Half-open transitions are debounced so a hinge passing through angles does
 * not make the layout flap; the first value and flat/closed states apply at once.
 */
@OptIn(FlowPreview::class)
@Composable
fun rememberFoldState(): FoldState {
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    val isFoldableDevice = remember(context) {
        context.packageManager.hasSystemFeature(PackageManager.FEATURE_SENSOR_HINGE_ANGLE)
    }
    val isExpandedWidth = currentWindowAdaptiveInfo().windowSizeClass
        .isWidthAtLeastBreakpoint(WindowSizeClass.WIDTH_DP_EXPANDED_LOWER_BOUND)

    val featureFlow: Flow<FoldFeature?> = remember(activity) {
        if (activity == null) {
            flowOf(null)
        } else {
            WindowInfoTracker.getOrCreate(activity)
                .windowLayoutInfo(activity)
                .map { info -> info.displayFeatures.filterIsInstance<FoldingFeature>().firstOrNull()?.toFoldFeature() }
                .distinctUntilChanged()
                .debounce { if (it?.halfOpened == true) NekoTokens.Motion.POSTURE_DEBOUNCE_MS else 0L }
        }
    }
    val feature by featureFlow.collectAsStateWithLifecycle(initialValue = null)
    return remember(feature, isFoldableDevice, isExpandedWidth) {
        deriveFoldState(feature, isFoldableDevice, isExpandedWidth)
    }
}

private fun FoldingFeature.toFoldFeature() = FoldFeature(
    halfOpened = state == FoldingFeature.State.HALF_OPENED,
    orientation = if (orientation == FoldingFeature.Orientation.HORIZONTAL) {
        HingeOrientation.Horizontal
    } else {
        HingeOrientation.Vertical
    },
    bounds = bounds.toComposeRect(),
    isSeparating = isSeparating,
)

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
