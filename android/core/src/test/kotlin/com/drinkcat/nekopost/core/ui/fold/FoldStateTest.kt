package com.drinkcat.nekopost.core.ui.fold

import androidx.compose.ui.geometry.Rect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FoldStateTest {
    private val horizontalHinge = Rect(0f, 1224f, 1848f, 1225f)
    private val verticalHinge = Rect(1224f, 0f, 1225f, 1848f)

    @Test
    fun halfOpenedHorizontalHingeIsTabletop() {
        val s = deriveFoldState(FoldFeature(true, HingeOrientation.Horizontal, horizontalHinge, true), true, false)
        assertEquals(Posture.Tabletop, s.posture)
        assertEquals(horizontalHinge, s.hinge)
        assertTrue(s.isSeparating)
        assertEquals(HingeOrientation.Horizontal, s.hingeOrientation)
    }

    @Test
    fun halfOpenedVerticalHingeIsBook() {
        val s = deriveFoldState(FoldFeature(true, HingeOrientation.Vertical, verticalHinge, true), true, true)
        assertEquals(Posture.Book, s.posture)
        assertEquals(HingeOrientation.Vertical, s.hingeOrientation)
    }

    @Test
    fun flatFeatureIsFlat() {
        val s = deriveFoldState(FoldFeature(false, HingeOrientation.Vertical, verticalHinge, false), true, true)
        assertEquals(Posture.Flat, s.posture)
        assertFalse(s.isHalfOpened)
        // A flat, non-separating hinge imposes no split.
        assertNull(s.hingeOrientation)
    }

    @Test
    fun foldableWithoutFeatureIsCoverWhenNotExpanded() {
        assertEquals(Posture.Cover, deriveFoldState(null, isFoldableDevice = true, isExpandedWidth = false).posture)
        assertEquals(Posture.Window, deriveFoldState(null, isFoldableDevice = true, isExpandedWidth = true).posture)
    }

    @Test
    fun ordinaryDeviceIsWindow() {
        assertEquals(Posture.Window, deriveFoldState(null, isFoldableDevice = false, isExpandedWidth = false).posture)
    }

    @Test
    fun injectedPosturesWithoutBoundsStillHaveAHingeOrientation() {
        assertEquals(HingeOrientation.Horizontal, FoldState.tabletop().hingeOrientation)
        assertEquals(HingeOrientation.Vertical, FoldState.book().hingeOrientation)
        assertNull(FoldState.Cover.hingeOrientation)
        assertNull(FoldState.Flat.hingeOrientation)
    }
}
