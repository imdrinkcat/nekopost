package com.drinkcat.nekopost.core.ui.component

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.drinkcat.nekopost.core.ui.motion.LocalReducedMotion
import com.drinkcat.nekopost.core.ui.motion.NekoMotion
import com.drinkcat.nekopost.core.ui.theme.NekopostTheme
import com.drinkcat.nekopost.core.ui.theme.StatusTone
import kotlin.math.PI

/** One arc of a [SegmentRing]: a component of the forwarding path and its state. */
@Immutable
data class RingSegment(val label: String, val tone: StatusTone, val state: String)

/**
 * The health ring (design §10.4 ④): one rounded arc per component, coloured by
 * its fixed status tone. Neutral segments ("off") are drawn as a faint track so
 * the ring still reads as a whole. Colour is never the only signal: callers
 * show a legend with text next to it, and the ring itself carries a merged
 * content description.
 */
@Composable
fun SegmentRing(
    segments: List<RingSegment>,
    modifier: Modifier = Modifier,
    size: Dp = 120.dp,
    thickness: Dp = 12.dp,
    center: @Composable BoxScope.() -> Unit = {},
) {
    val reduced = LocalReducedMotion.current
    val tones = NekopostTheme.colors
    val off = MaterialTheme.colorScheme.outlineVariant
    val colors: List<Color> = segments.map { s ->
        key(s.label) {
            val target = if (s.tone == StatusTone.Neutral) off else tones.tone(s.tone).content
            animateColorAsState(target, animationSpec = NekoMotion.effects(reduced), label = "ring").value
        }
    }
    val description = segments.joinToString("，") { "${it.label}${it.state}" }
    Box(
        modifier = modifier
            .size(size)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val n = segments.size
            if (n == 0) return@Canvas
            val stroke = thickness.toPx()
            val radius = (this.size.minDimension - stroke) / 2f
            val topLeft = Offset(this.size.width / 2f - radius, this.size.height / 2f - radius)
            val arcSize = Size(radius * 2f, radius * 2f)
            // Round caps grow each arc by half the stroke at both ends; subtract that
            // so the visible gap stays constant whatever the size.
            val capDegrees = (stroke / 2f) / radius * (180f / PI.toFloat())
            val gapDegrees = if (n > 1) 7f else 0f
            val slot = 360f / n
            val sweep = (slot - gapDegrees - 2f * capDegrees).coerceAtLeast(1f)
            for (i in 0 until n) {
                drawArc(
                    color = colors[i],
                    startAngle = -90f + i * slot + gapDegrees / 2f + capDegrees,
                    sweepAngle = if (n == 1) 360f else sweep,
                    useCenter = false,
                    topLeft = topLeft,
                    size = arcSize,
                    style = Stroke(width = stroke, cap = if (n == 1) StrokeCap.Butt else StrokeCap.Round),
                )
            }
        }
        center()
    }
}
