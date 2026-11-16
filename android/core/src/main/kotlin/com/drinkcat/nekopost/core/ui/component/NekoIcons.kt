package com.drinkcat.nekopost.core.ui.component

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * Hand-drawn line icons on the 24dp grid with 2dp round strokes (design §9.5),
 * for concepts the Material core icon set does not cover. Tinted like any
 * other icon (the stroke colour is replaced by LocalContentColor).
 */
object NekoIcons {
    val Paw: ImageVector by lazy {
        icon("Paw", filled = true) {
            circle(12f, 15.5f, 3.6f)
            circle(6.2f, 11.2f, 1.9f)
            circle(9.4f, 7.4f, 1.9f)
            circle(14.6f, 7.4f, 1.9f)
            circle(17.8f, 11.2f, 1.9f)
        }
    }

    val QrScan: ImageVector by lazy {
        icon("QrScan") {
            // Corner brackets.
            moveTo(3f, 8f); lineTo(3f, 4.5f); quadTo(3f, 3f, 4.5f, 3f); lineTo(8f, 3f)
            moveTo(16f, 3f); lineTo(19.5f, 3f); quadTo(21f, 3f, 21f, 4.5f); lineTo(21f, 8f)
            moveTo(21f, 16f); lineTo(21f, 19.5f); quadTo(21f, 21f, 19.5f, 21f); lineTo(16f, 21f)
            moveTo(8f, 21f); lineTo(4.5f, 21f); quadTo(3f, 21f, 3f, 19.5f); lineTo(3f, 16f)
            // Finder squares and a few modules.
            rect(7f, 7f, 4f, 4f)
            rect(13f, 7f, 4f, 4f)
            rect(7f, 13f, 4f, 4f)
            moveTo(14f, 14f); lineTo(14f, 14.01f)
            moveTo(17f, 17f); lineTo(17f, 17.01f)
            moveTo(14f, 17f); lineTo(14f, 17.01f)
            moveTo(17f, 14f); lineTo(17f, 14.01f)
        }
    }

    val Sms: ImageVector by lazy {
        icon("Sms") {
            moveTo(5f, 4f); lineTo(19f, 4f); quadTo(21f, 4f, 21f, 6f); lineTo(21f, 15f); quadTo(21f, 17f, 19f, 17f)
            lineTo(10f, 17f); lineTo(5.5f, 20.5f); lineTo(6f, 17f); lineTo(5f, 17f); quadTo(3f, 17f, 3f, 15f)
            lineTo(3f, 6f); quadTo(3f, 4f, 5f, 4f); close()
            moveTo(7.5f, 9f); lineTo(16.5f, 9f)
            moveTo(7.5f, 12.5f); lineTo(13f, 12.5f)
        }
    }

    val Battery: ImageVector by lazy {
        icon("Battery") {
            moveTo(9f, 3f); lineTo(15f, 3f)
            moveTo(8f, 5f); lineTo(16f, 5f); quadTo(18f, 5f, 18f, 7f); lineTo(18f, 19f); quadTo(18f, 21f, 16f, 21f)
            lineTo(8f, 21f); quadTo(6f, 21f, 6f, 19f); lineTo(6f, 7f); quadTo(6f, 5f, 8f, 5f); close()
            // Bolt.
            moveTo(13f, 8.5f); lineTo(10f, 13f); lineTo(14f, 13f); lineTo(11f, 17.5f)
        }
    }

    val Shield: ImageVector by lazy {
        icon("Shield") {
            moveTo(12f, 3f); lineTo(19.5f, 6f); lineTo(19.5f, 11.5f)
            curveTo(19.5f, 16f, 16.4f, 19.6f, 12f, 21f)
            curveTo(7.6f, 19.6f, 4.5f, 16f, 4.5f, 11.5f)
            lineTo(4.5f, 6f); close()
            moveTo(9f, 12f); lineTo(11.2f, 14.2f); lineTo(15.2f, 10f)
        }
    }

    /** A folded phone showing its cover display. */
    val CoverScreen: ImageVector by lazy {
        icon("CoverScreen") {
            moveTo(8f, 2.5f); lineTo(16f, 2.5f); quadTo(18f, 2.5f, 18f, 4.5f); lineTo(18f, 19.5f); quadTo(18f, 21.5f, 16f, 21.5f)
            lineTo(8f, 21.5f); quadTo(6f, 21.5f, 6f, 19.5f); lineTo(6f, 4.5f); quadTo(6f, 2.5f, 8f, 2.5f); close()
            moveTo(18f, 5f); lineTo(20f, 6f); lineTo(20f, 18f); lineTo(18f, 19f)
            moveTo(10.5f, 18.5f); lineTo(13.5f, 18.5f)
        }
    }

    /** Envelope with a heartbeat line: "test message". */
    val TestLetter: ImageVector by lazy {
        icon("TestLetter") {
            moveTo(4.5f, 5f); lineTo(19.5f, 5f); quadTo(21f, 5f, 21f, 6.5f); lineTo(21f, 17.5f); quadTo(21f, 19f, 19.5f, 19f)
            lineTo(4.5f, 19f); quadTo(3f, 19f, 3f, 17.5f); lineTo(3f, 6.5f); quadTo(3f, 5f, 4.5f, 5f); close()
            moveTo(6f, 13f); lineTo(9f, 13f); lineTo(10.5f, 9.5f); lineTo(13f, 16f); lineTo(14.5f, 12f); lineTo(18f, 12f)
        }
    }

    /** Post box with a slot: the brand "邮筒". */
    val PostBox: ImageVector by lazy {
        icon("PostBox") {
            moveTo(6f, 21f); lineTo(6f, 9f); curveTo(6f, 5.7f, 8.7f, 3f, 12f, 3f); curveTo(15.3f, 3f, 18f, 5.7f, 18f, 9f); lineTo(18f, 21f)
            moveTo(4.5f, 21f); lineTo(19.5f, 21f)
            moveTo(9f, 9.5f); lineTo(15f, 9.5f)
            moveTo(10f, 14.5f); lineTo(14f, 14.5f)
        }
    }

    /** Two stacked server units with status lights: "your server". */
    val Server: ImageVector by lazy {
        icon("Server") {
            moveTo(5f, 4f); lineTo(19f, 4f); quadTo(20.5f, 4f, 20.5f, 5.5f); lineTo(20.5f, 9f); quadTo(20.5f, 10.5f, 19f, 10.5f)
            lineTo(5f, 10.5f); quadTo(3.5f, 10.5f, 3.5f, 9f); lineTo(3.5f, 5.5f); quadTo(3.5f, 4f, 5f, 4f); close()
            moveTo(5f, 13.5f); lineTo(19f, 13.5f); quadTo(20.5f, 13.5f, 20.5f, 15f); lineTo(20.5f, 18.5f); quadTo(20.5f, 20f, 19f, 20f)
            lineTo(5f, 20f); quadTo(3.5f, 20f, 3.5f, 18.5f); lineTo(3.5f, 15f); quadTo(3.5f, 13.5f, 5f, 13.5f); close()
            moveTo(7f, 7.25f); lineTo(7f, 7.26f)
            moveTo(7f, 16.75f); lineTo(7f, 16.76f)
            moveTo(11f, 7.25f); lineTo(17f, 7.25f)
            moveTo(11f, 16.75f); lineTo(17f, 16.75f)
        }
    }

    private fun icon(name: String, filled: Boolean = false, block: PathBuilder.() -> Unit): ImageVector =
        ImageVector.Builder(
            name = "Neko.$name",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            if (filled) {
                path(fill = SolidColor(Color.Black), pathBuilder = block)
            } else {
                path(
                    fill = null,
                    stroke = SolidColor(Color.Black),
                    strokeLineWidth = 2f,
                    strokeLineCap = StrokeCap.Round,
                    strokeLineJoin = StrokeJoin.Round,
                    pathBuilder = block,
                )
            }
        }.build()

    private fun PathBuilder.circle(cx: Float, cy: Float, r: Float) {
        moveTo(cx - r, cy)
        arcTo(r, r, 0f, isMoreThanHalf = true, isPositiveArc = true, x1 = cx + r, y1 = cy)
        arcTo(r, r, 0f, isMoreThanHalf = true, isPositiveArc = true, x1 = cx - r, y1 = cy)
        close()
    }

    private fun PathBuilder.rect(x: Float, y: Float, w: Float, h: Float) {
        moveTo(x, y); lineTo(x + w, y); lineTo(x + w, y + h); lineTo(x, y + h); close()
    }
}
