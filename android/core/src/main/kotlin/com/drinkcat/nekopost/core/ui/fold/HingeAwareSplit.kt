package com.drinkcat.nekopost.core.ui.fold

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.drinkcat.nekopost.core.ui.token.NekoTokens
import kotlin.math.roundToInt

/** Direction in which [HingeAwareSplit] stacks its two panes. */
enum class SplitAxis {
    /** [first] on top, [second] below (respects a horizontal hinge: tabletop). */
    Vertical,

    /** [first] at the start, [second] at the end (respects a vertical hinge: book). */
    Horizontal,
}

/**
 * Two panes split either exactly at a separating hinge or at [fraction].
 *
 * When [fold] has a hinge whose line runs across [axis] (horizontal hinge for
 * [SplitAxis.Vertical], vertical hinge for [SplitAxis.Horizontal]) and the
 * device is half open or the hinge is separating, the split follows the hinge:
 * the hinge area itself plus [NekoTokens.Hinge.keepOut] on each side stay
 * empty, so no text or touch target ever crosses or hugs the fold. Without
 * known bounds (injected postures) the hinge is assumed at the centre.
 *
 * Otherwise the panes are split at [fraction] with [gap] between them.
 * The layout fills the incoming bounded constraints.
 */
@Composable
fun HingeAwareSplit(
    fold: FoldState,
    axis: SplitAxis,
    modifier: Modifier = Modifier,
    fraction: Float = 0.5f,
    gap: Dp = 0.dp,
    first: @Composable () -> Unit,
    second: @Composable () -> Unit,
) {
    var origin by remember { mutableStateOf(Offset.Zero) }
    val followsHinge = when (axis) {
        SplitAxis.Vertical -> fold.hingeOrientation == HingeOrientation.Horizontal
        SplitAxis.Horizontal -> fold.hingeOrientation == HingeOrientation.Vertical
    } && (fold.isSeparating || fold.isHalfOpened)

    Layout(
        contents = listOf(first, second),
        modifier = modifier.onPlaced { origin = it.positionInWindow() },
    ) { (firstMeasurables, secondMeasurables), constraints ->
        val width = constraints.maxWidth.takeIf { it != Constraints.Infinity } ?: constraints.minWidth
        val height = constraints.maxHeight.takeIf { it != Constraints.Infinity } ?: constraints.minHeight
        val total = if (axis == SplitAxis.Vertical) height else width

        val (firstEnd, secondStart) = if (followsHinge) {
            val keepOut = NekoTokens.Hinge.keepOut.roundToPx()
            val hinge = fold.hinge
            val (hingeStart, hingeEnd) = if (hinge != null) {
                // Window coordinates -> this layout's coordinates.
                if (axis == SplitAxis.Vertical) {
                    (hinge.top - origin.y) to (hinge.bottom - origin.y)
                } else {
                    (hinge.left - origin.x) to (hinge.right - origin.x)
                }
            } else {
                (total / 2f) to (total / 2f)
            }
            val a = (hingeStart.roundToInt() - keepOut).coerceIn(0, total)
            val b = (hingeEnd.roundToInt() + keepOut).coerceIn(a, total)
            a to b
        } else {
            val gapPx = gap.roundToPx()
            val a = ((total - gapPx) * fraction.coerceIn(0f, 1f)).roundToInt().coerceIn(0, total)
            a to (a + gapPx).coerceAtMost(total)
        }

        val firstSize = firstEnd
        val secondSize = total - secondStart
        fun fixed(main: Int) = if (axis == SplitAxis.Vertical) {
            Constraints.fixed(width, main.coerceAtLeast(0))
        } else {
            Constraints.fixed(main.coerceAtLeast(0), height)
        }
        val firstPlaceables = firstMeasurables.map { it.measure(fixed(firstSize)) }
        val secondPlaceables = secondMeasurables.map { it.measure(fixed(secondSize)) }

        layout(width, height) {
            firstPlaceables.forEach { it.placeRelative(0, 0) }
            secondPlaceables.forEach {
                if (axis == SplitAxis.Vertical) it.placeRelative(0, secondStart) else it.placeRelative(secondStart, 0)
            }
        }
    }
}
