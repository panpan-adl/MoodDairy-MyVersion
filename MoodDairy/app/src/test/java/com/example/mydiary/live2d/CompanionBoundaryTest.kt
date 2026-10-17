package com.example.mydiary.live2d

import org.junit.Assert.*
import org.junit.Test

class CompanionBoundaryTest {
    @Test fun overlayBottomMatchesComposerInWindowCoordinates() {
        val top = CompanionAnchorGeometry.top(900f, 24, 8, 220)
        assertEquals(900, top + 220 + 24 + 8)
    }
    @Test fun keyboardAndExpandedInputMoveTheBottomWithTheBoundary() {
        for (boundary in listOf(900f, 620f, 580f, 900f)) {
            val top = CompanionAnchorGeometry.top(boundary, 24, 0, 220)
            assertEquals(boundary.toInt(), top + 220 + 24)
        }
    }
    @Test fun bubbleHeightDoesNotMoveTheCharacterBottom() {
        for (height in listOf(190, 240, 300, 190)) {
            assertEquals(700, CompanionAnchorGeometry.top(700f, 0, 0, height) + height)
        }
    }
    @Test fun horizontalDragStaysInsideParentAndDoesNotDependOnVerticalMotion() {
        assertEquals(0, CompanionAnchorGeometry.horizontal(-200, 400, 8, 8, 190))
        assertEquals(194, CompanionAnchorGeometry.horizontal(999, 400, 8, 8, 190))
        assertEquals(100, CompanionAnchorGeometry.horizontal(100, 400, 8, 8, 190))
        assertEquals(0, CompanionAnchorGeometry.horizontal(100, 120, 0, 0, 190))
    }
    @Test fun oldPageDisposalCannotEraseNewPageBoundary() {
        val boundary = CompanionBoundary()
        val first = Any(); val second = Any()
        assertNull(boundary.topInWindow)
        boundary.update(first, 900f)
        boundary.update(second, 600f)
        boundary.update(first, null)
        assertEquals(600f, boundary.topInWindow)
        boundary.update(second, null)
        assertNull(boundary.topInWindow)
    }
    @Test fun invalidBoundaryDoesNotExposeAnUnpositionedOverlay() {
        val boundary = CompanionBoundary()
        val owner = Any()
        for (value in listOf(Float.NaN, Float.POSITIVE_INFINITY, -1f)) boundary.update(owner, value)
        assertNull(boundary.topInWindow)
    }
}
