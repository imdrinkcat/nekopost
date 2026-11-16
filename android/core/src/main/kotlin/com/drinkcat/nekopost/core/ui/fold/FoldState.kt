package com.drinkcat.nekopost.core.ui.fold

import androidx.compose.runtime.Immutable
import androidx.compose.ui.geometry.Rect

/** Device posture as far as layout is concerned (design §10.2). */
enum class Posture {
    /** Folded phone on its cover display (no folding feature, compact width). */
    Cover,

    /** Inner display fully open. */
    Flat,

    /** Half open with a horizontal hinge: top half to look at, bottom half to touch. */
    Tabletop,

    /** Half open with a vertical hinge: two pages. */
    Book,

    /** Anything else: ordinary phone, tablet, split screen or desktop window. */
    Window,
}

/** Orientation of the hinge line itself (horizontal hinge = tabletop). */
enum class HingeOrientation { Horizontal, Vertical }

/**
 * The single source of posture for all screens. Screens take it as a plain
 * parameter, so previews and screenshot tests can inject any posture without
 * a real FoldingFeature.
 *
 * @property hinge hinge bounds in window coordinates (px), when known.
 * @property hingeAngle hinge angle in degrees from Sensor.TYPE_HINGE_ANGLE, when available.
 * @property isSeparating true when the hinge splits the window into two logical areas.
 */
@Immutable
data class FoldState(
    val posture: Posture,
    val hinge: Rect? = null,
    val hingeAngle: Float? = null,
    val isSeparating: Boolean = false,
) {
    val isHalfOpened: Boolean get() = posture == Posture.Tabletop || posture == Posture.Book

    /**
     * Orientation of the hinge line, if the layout has to respect one. Falls back
     * to the posture when no bounds are known (injected postures in tests).
     */
    val hingeOrientation: HingeOrientation?
        get() = when {
            hinge != null && (isSeparating || isHalfOpened) ->
                if (hinge.width >= hinge.height) HingeOrientation.Horizontal else HingeOrientation.Vertical
            posture == Posture.Tabletop -> HingeOrientation.Horizontal
            posture == Posture.Book -> HingeOrientation.Vertical
            else -> null
        }

    companion object {
        val Window = FoldState(Posture.Window)
        val Cover = FoldState(Posture.Cover)
        val Flat = FoldState(Posture.Flat)

        /** Tabletop posture; without [hinge] the hinge is assumed at the vertical centre. */
        fun tabletop(hinge: Rect? = null) = FoldState(Posture.Tabletop, hinge = hinge, isSeparating = true)

        /** Book posture; without [hinge] the hinge is assumed at the horizontal centre. */
        fun book(hinge: Rect? = null) = FoldState(Posture.Book, hinge = hinge, isSeparating = true)
    }
}

/** A snapshot of androidx.window's FoldingFeature in plain Kotlin types (unit-testable). */
data class FoldFeature(
    val halfOpened: Boolean,
    val orientation: HingeOrientation,
    val bounds: Rect,
    val isSeparating: Boolean,
)

/**
 * Derivation rules (design §10.2):
 *  - Tabletop = HALF_OPENED with a horizontal hinge
 *  - Book     = HALF_OPENED with a vertical hinge
 *  - Flat     = a FLAT folding feature
 *  - Cover    = no folding feature on a foldable device, narrower than Expanded
 *  - Window   = everything else
 */
fun deriveFoldState(
    feature: FoldFeature?,
    isFoldableDevice: Boolean,
    isExpandedWidth: Boolean,
    hingeAngle: Float? = null,
): FoldState = when {
    feature != null && feature.halfOpened -> FoldState(
        posture = if (feature.orientation == HingeOrientation.Horizontal) Posture.Tabletop else Posture.Book,
        hinge = feature.bounds,
        hingeAngle = hingeAngle,
        isSeparating = feature.isSeparating,
    )
    feature != null -> FoldState(
        posture = Posture.Flat,
        hinge = feature.bounds,
        hingeAngle = hingeAngle,
        isSeparating = feature.isSeparating,
    )
    isFoldableDevice && !isExpandedWidth -> FoldState(Posture.Cover, hingeAngle = hingeAngle)
    else -> FoldState(Posture.Window, hingeAngle = hingeAngle)
}
