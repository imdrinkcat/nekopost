package com.drinkcat.nekopost.system

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.window.core.layout.WindowSizeClass
import androidx.window.layout.FoldingFeature
import androidx.window.layout.WindowInfoTracker
import androidx.window.layout.WindowMetricsCalculator
import com.drinkcat.nekopost.core.ui.fold.FoldState
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlin.math.roundToInt

/**
 * Snapshot for the foldable debug panel (design §10.7). Contains no secrets:
 * the user may paste [spec] back to calibrate the screenshot matrix.
 */
@Immutable
data class FoldDebugInfo(
    val widthPx: Int,
    val heightPx: Int,
    val widthDp: Int,
    val heightDp: Int,
    val density: Float,
    val densityDpi: Int,
    val fontScale: Float,
    val widthClass: String,
    val heightClass: String,
    /** Human-readable FoldingFeature, or null when the window has none. */
    val foldingFeature: String?,
    val posture: String,
    val hingeAngle: Float?,
) {
    /** Robolectric/Compose preview device spec for this window. */
    val spec: String get() = "spec:width=${widthDp}dp,height=${heightDp}dp,dpi=$densityDpi"
}

private fun widthClassName(c: WindowSizeClass): String = when {
    c.isWidthAtLeastBreakpoint(WindowSizeClass.WIDTH_DP_LARGE_LOWER_BOUND) -> "LARGE (≥1200)"
    c.isWidthAtLeastBreakpoint(WindowSizeClass.WIDTH_DP_EXPANDED_LOWER_BOUND) -> "EXPANDED (≥840)"
    c.isWidthAtLeastBreakpoint(WindowSizeClass.WIDTH_DP_MEDIUM_LOWER_BOUND) -> "MEDIUM (≥600)"
    else -> "COMPACT"
}

private fun heightClassName(c: WindowSizeClass): String = when {
    c.isHeightAtLeastBreakpoint(WindowSizeClass.HEIGHT_DP_EXPANDED_LOWER_BOUND) -> "EXPANDED (≥900)"
    c.isHeightAtLeastBreakpoint(WindowSizeClass.HEIGHT_DP_MEDIUM_LOWER_BOUND) -> "MEDIUM (≥480)"
    else -> "COMPACT"
}

private fun FoldingFeature.describe(): String =
    "state=$state orientation=$orientation isSeparating=$isSeparating occlusion=$occlusionType " +
        "bounds=[${bounds.left},${bounds.top},${bounds.right},${bounds.bottom}]"

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

/** Live debug snapshot; recomposes on configuration and folding-feature changes. */
@Composable
fun rememberFoldDebugInfo(fold: FoldState): FoldDebugInfo {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val density = LocalDensity.current
    val activity = remember(context) { context.findActivity() }
    val sizeClass = currentWindowAdaptiveInfo().windowSizeClass
    val featureFlow = remember(activity) {
        if (activity == null) {
            flowOf(null)
        } else {
            WindowInfoTracker.getOrCreate(activity).windowLayoutInfo(activity)
                .map { info -> info.displayFeatures.filterIsInstance<FoldingFeature>().firstOrNull()?.describe() }
        }
    }
    val feature by featureFlow.collectAsStateWithLifecycle(initialValue = null)
    val bounds = remember(activity, configuration) {
        activity?.let { WindowMetricsCalculator.getOrCreate().computeCurrentWindowMetrics(it).bounds }
    }
    val container = LocalWindowInfo.current.containerSize
    val widthPx = bounds?.width() ?: container.width
    val heightPx = bounds?.height() ?: container.height
    return FoldDebugInfo(
        widthPx = widthPx,
        heightPx = heightPx,
        widthDp = (widthPx / density.density).roundToInt(),
        heightDp = (heightPx / density.density).roundToInt(),
        density = density.density,
        densityDpi = configuration.densityDpi,
        fontScale = density.fontScale,
        widthClass = widthClassName(sizeClass),
        heightClass = heightClassName(sizeClass),
        foldingFeature = feature,
        posture = fold.posture.name,
        hingeAngle = fold.hingeAngle,
    )
}
