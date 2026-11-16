package com.drinkcat.nekopost.core.ui.component

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.drinkcat.nekopost.core.ui.token.NekoTokens

/**
 * The sleeping courier cat curled on top of a letter: a quiet, geometric mark
 * used for empty and idle states only (never on error, security or data views).
 * Static by design; animation, if any, belongs to the caller and must respect
 * reduced motion.
 */
@Composable
fun SleepingCat(
    modifier: Modifier = Modifier,
    size: Dp = 96.dp,
    fur: Color = MaterialTheme.colorScheme.primaryContainer,
    ink: Color = MaterialTheme.colorScheme.onPrimaryContainer,
    paper: Color = MaterialTheme.colorScheme.surfaceContainerLowest,
) {
    Canvas(modifier = modifier.size(size)) {
        drawSleepingCat(fur = fur, ink = ink, paper = paper)
    }
}

private fun DrawScope.drawSleepingCat(fur: Color, ink: Color, paper: Color) {
    // Drawn on a 100 x 100 grid.
    val u = size.minDimension / 100f
    fun p(x: Float, y: Float) = Offset(x * u, y * u)
    val stroke = Stroke(width = 2.5f * u, cap = StrokeCap.Round, join = StrokeJoin.Round)

    // Letter under the cat.
    drawRoundRect(color = paper, topLeft = p(14f, 58f), size = Size(72f * u, 34f * u), cornerRadius = CornerRadius(4f * u))
    drawRoundRect(color = ink, topLeft = p(14f, 58f), size = Size(72f * u, 34f * u), cornerRadius = CornerRadius(4f * u), style = stroke)
    val flap = Path().apply {
        moveTo(14f * u, 60f * u)
        lineTo(50f * u, 80f * u)
        lineTo(86f * u, 60f * u)
    }
    drawPath(flap, color = ink, style = stroke)

    // Tail curling around the body.
    val tail = Path().apply {
        moveTo(74f * u, 58f * u)
        cubicTo(92f * u, 56f * u, 92f * u, 36f * u, 78f * u, 38f * u)
    }
    drawPath(tail, color = fur, style = Stroke(width = 7f * u, cap = StrokeCap.Round))
    drawPath(tail, color = ink, style = Stroke(width = 1.6f * u, cap = StrokeCap.Round))

    // Body: a loaf.
    drawRoundRect(color = fur, topLeft = p(30f, 36f), size = Size(48f * u, 26f * u), cornerRadius = CornerRadius(13f * u))
    drawRoundRect(color = ink, topLeft = p(30f, 36f), size = Size(48f * u, 26f * u), cornerRadius = CornerRadius(13f * u), style = stroke)

    // Head with ears.
    val head = Path().apply {
        moveTo(20f * u, 40f * u)
        lineTo(22f * u, 20f * u)
        lineTo(32f * u, 29f * u)
        lineTo(40f * u, 29f * u)
        lineTo(50f * u, 20f * u)
        lineTo(52f * u, 40f * u)
        cubicTo(52f * u, 60f * u, 20f * u, 60f * u, 20f * u, 40f * u)
        close()
    }
    drawPath(head, color = fur)
    drawPath(head, color = ink, style = stroke)

    // Closed eyes and a tiny nose.
    val eyes = Path().apply {
        moveTo(26f * u, 41f * u)
        quadraticTo(29.5f * u, 44f * u, 33f * u, 41f * u)
        moveTo(39f * u, 41f * u)
        quadraticTo(42.5f * u, 44f * u, 46f * u, 41f * u)
    }
    drawPath(eyes, color = ink, style = Stroke(width = 2f * u, cap = StrokeCap.Round))
    drawCircle(color = ink, radius = 1.4f * u, center = p(36f, 46f))

    // "z" floating above.
    val z = Path().apply {
        moveTo(58f * u, 14f * u)
        lineTo(66f * u, 14f * u)
        lineTo(58f * u, 22f * u)
        lineTo(66f * u, 22f * u)
    }
    drawPath(z, color = ink.copy(alpha = 0.6f), style = Stroke(width = 1.8f * u, cap = StrokeCap.Round, join = StrokeJoin.Round))
}

/** Empty state: cat mark, a short title and one explanatory line. */
@Composable
fun EmptyState(
    title: String,
    message: String,
    modifier: Modifier = Modifier,
    showCat: Boolean = true,
) {
    Column(
        modifier = modifier.fillMaxWidth().padding(NekoTokens.Spacing.xl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(NekoTokens.Spacing.m),
    ) {
        if (showCat) SleepingCat()
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
        )
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}
