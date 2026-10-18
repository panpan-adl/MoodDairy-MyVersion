package com.example.mydiary.ui.components

import org.junit.Assert.*
import org.junit.Test

class PageScrollbarGeometryTest {
    private fun fraction(index: Int, offset: Int = 0, reverse: Boolean = false,
        backward: Boolean = index > 0 || offset > 0, forward: Boolean = index < 7) =
        lazyScrollbarFraction(10, index, offset, 100,
            listOf(ScrollbarItem(-offset, 100), ScrollbarItem(100 - offset, 100), ScrollbarItem(200 - offset, 100)),
            0, 300, backward, forward, reverse)

    @Test fun firstAndLastPositionsMatchTrackEndpoints() {
        assertEquals(0f, fraction(0).progress, .001f)
        assertEquals(1f, fraction(7).progress, .001f)
        assertEquals(.3f, fraction(0).visible, .001f)
    }
    @Test fun reverseChatStartsAtTheBottomAndHistoryEndsAtTheTop() {
        assertEquals(1f, fraction(0, reverse = true).progress, .001f)
        assertEquals(0f, fraction(7, reverse = true).progress, .001f)
    }
    @Test fun middleOfListProducesProportionalPosition() {
        val result = fraction(3)
        assertEquals(3f / 7, result.progress, .001f)
        assertEquals(300f, scrollbarThumb(1000f, result, 32f).top, .001f)
    }
    @Test fun aSingleOversizedItemCanStillScroll() {
        val result = lazyScrollbarFraction(1, 0, 400, 1000,
            listOf(ScrollbarItem(-400, 1000)), 0, 200, true, true, false)
        assertEquals(.2f, result.visible, .001f)
        assertEquals(.5f, result.progress, .001f)
    }
    @Test fun clippedRowsOnlyCountTheirVisiblePortion() {
        val result = fraction(2, 50)
        assertEquals(.25f, result.visible, .001f)
        assertTrue(result.progress > 0f && result.progress < 1f)
    }
    @Test fun emptyOrUnmeasuredContentDoesNotCreateInvalidGeometry() {
        val empty = lazyScrollbarFraction(0, 0, 0, 0, emptyList(), 0, 0, false, false, false)
        assertEquals(1f, empty.visible, .001f)
        assertEquals(ScrollbarThumb(0f, 0f), scrollbarThumb(0f, empty, 32f))
    }
    @Test fun thumbHasAMinimumSizeAndStaysInsideShortTracks() {
        assertEquals(ScrollbarThumb(68f, 32f), scrollbarThumb(100f, ScrollbarFraction(1f, .001f), 32f))
        assertEquals(ScrollbarThumb(0f, 12f), scrollbarThumb(12f, ScrollbarFraction(1f, .001f), 32f))
    }
}
