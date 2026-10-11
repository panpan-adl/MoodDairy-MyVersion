package com.example.mydiary.ml

import org.junit.Assert.*
import org.junit.Test

class FaceCropGeometryTest {
    @Test fun rectangularFaceGetsSquareWithContext() {
        val b = FaceCropGeometry.square(100, 80, 200, 230, 640, 480)!!
        assertTrue(b.size >= 150)
        assertTrue(b.left <= 100 && b.left + b.size >= 200)
        assertTrue(b.top <= 80 && b.top + b.size >= 230)
    }
    @Test fun edgeCropShiftsWithoutChangingAspectRatio() {
        val b = FaceCropGeometry.square(0, 0, 90, 120, 640, 480)!!
        assertEquals(0, b.left); assertEquals(0, b.top)
        assertTrue(b.left + b.size <= 640 && b.top + b.size <= 480)
    }
    @Test fun slightDetectorOvershootIsAcceptedAtEachEdge() {
        for (box in listOf(intArrayOf(-8, 0, 90, 120), intArrayOf(0, -8, 90, 120),
            intArrayOf(550, 300, 648, 480), intArrayOf(100, 370, 220, 488))) {
            assertNotNull(FaceCropGeometry.square(box[0], box[1], box[2], box[3], 640, 480))
        }
    }
    @Test fun closeFaceLargerThanShortSideUsesPaddingInsteadOfRejection() {
        val b = FaceCropGeometry.square(0, 0, 600, 400, 640, 480)!!
        assertTrue(b.size >= 600)
        assertTrue(b.top < 0)
    }
    @Test fun mostlyMissingOrInvalidFacesAreRejected() {
        assertNull(FaceCropGeometry.square(-100, 0, 90, 120, 640, 480))
        assertNull(FaceCropGeometry.square(650, 0, 750, 120, 640, 480))
        assertNull(FaceCropGeometry.square(0, 0, 0, 10, 640, 480))
    }
}
