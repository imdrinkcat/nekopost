package com.drinkcat.nekopost.core.ui.component

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.drinkcat.nekopost.core.ui.motion.LocalReducedMotion
import com.drinkcat.nekopost.core.ui.theme.NekoTextStyles
import kotlinx.coroutines.launch

/**
 * A round postmark: double ring, a paw print at the top, three lines of text
 * and the classic wavy cancellation lines. Used when setup is finished.
 */
@Composable
fun PostmarkStamp(
    title: String,
    date: String,
    caption: String,
    modifier: Modifier = Modifier,
    size: Dp = 176.dp,
    ink: Color = MaterialTheme.colorScheme.primary,
) {
    Box(
        modifier = modifier
            .size(size)
            .semantics { contentDescription = "$title $date $caption" },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxSize()) { drawPostmark(ink) }
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = title,
                color = ink,
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold, fontSize = (size.value / 7.5f).sp),
                textAlign = TextAlign.Center,
            )
            Text(text = date, color = ink, style = NekoTextStyles.mono, textAlign = TextAlign.Center)
            Text(text = caption, color = ink, style = MaterialTheme.typography.labelLarge, textAlign = TextAlign.Center)
        }
    }
}

private fun DrawScope.drawPostmark(ink: Color) {
    val r = size.minDimension / 2f
    val c = Offset(size.width / 2f, size.height / 2f)
    drawCircle(ink, radius = r - 3.dp.toPx(), center = c, style = Stroke(width = 3.dp.toPx()))
    drawCircle(
        ink.copy(alpha = 0.7f),
        radius = r - 11.dp.toPx(),
        center = c,
        style = Stroke(width = 1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f))),
    )
    // Paw print near the top.
    val pawCenter = Offset(c.x, c.y - r * 0.56f)
    val pad = r * 0.085f
    drawCircle(ink, radius = pad, center = pawCenter.copy(y = pawCenter.y + pad * 0.5f))
    val toe = pad * 0.45f
    listOf(-1.25f to -0.55f, -0.45f to -1.2f, 0.45f to -1.2f, 1.25f to -0.55f).forEach { (dx, dy) ->
        drawCircle(ink, radius = toe, center = Offset(pawCenter.x + dx * pad, pawCenter.y + dy * pad))
    }
    // Two short cancellation strokes at the bottom.
    val stroke = Stroke(width = 1.5.dp.toPx(), cap = StrokeCap.Round)
    for (k in 0..1) {
        val y = c.y + r * (0.58f + k * 0.12f)
        drawLine(ink.copy(alpha = 0.6f), Offset(c.x - r * 0.32f, y), Offset(c.x + r * 0.32f, y), strokeWidth = stroke.width, cap = StrokeCap.Round)
    }
}

/**
 * Plays the "stamp lands on the letter" motion once: the stamp drops from
 * above (scaled up, faded out, tilted), lands with a slight bounce and a
 * confirm haptic, and rests at a jaunty -8 degrees. With reduced motion the
 * stamp is simply shown in its resting state.
 */
@Composable
fun StampLanding(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val reduced = LocalReducedMotion.current
    val haptics = LocalHapticFeedback.current
    val progress = remember { Animatable(if (reduced) 1f else 0f) }
    LaunchedEffect(Unit) {
        if (!reduced && progress.value < 1f) {
            launch {
                progress.animateTo(1f, spring(dampingRatio = 0.45f, stiffness = Spring.StiffnessLow))
            }
            // The stamp "hits" a little before the spring settles.
            kotlinx.coroutines.delay(260)
            haptics.performHapticFeedback(HapticFeedbackType.Confirm)
        }
    }
    Box(
        modifier = modifier.graphicsLayer {
            val p = progress.value
            val scale = 1.8f - 0.8f * p
            scaleX = scale
            scaleY = scale
            alpha = (p * 1.6f).coerceIn(0f, 1f)
            rotationZ = -24f + 16f * p
        },
    ) {
        content()
    }
}

/** Fades content in once (or shows it at once with reduced motion). */
@Composable
fun FadeInOnce(modifier: Modifier = Modifier, delayMillis: Int = 0, content: @Composable () -> Unit) {
    val reduced = LocalReducedMotion.current
    val alpha = remember { Animatable(if (reduced) 1f else 0f) }
    LaunchedEffect(Unit) {
        if (!reduced) alpha.animateTo(1f, tween(durationMillis = 420, delayMillis = delayMillis, easing = FastOutSlowInEasing))
    }
    Box(modifier.graphicsLayer { this.alpha = alpha.value }) { content() }
}
