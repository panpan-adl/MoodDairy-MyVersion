package com.example.mydiary.ml

import org.junit.Assert.assertEquals
import org.junit.Test

class NegativeEmotionDurationTrackerTest {
    @Test fun reachesTenSecondsOnlyAfterContinuousObservations() {
        val tracker = NegativeEmotionDurationTracker()
        for (t in 0L..10_000L step 2_000L) {
            assertEquals(t, tracker.observe("sad", 0.8f, t))
        }
    }

    @Test fun neutralOrMissingFaceResetsDuration() {
        for (label in listOf("neutral", "happy", null)) {
            val tracker = NegativeEmotionDurationTracker()
            tracker.observe("sad", 0.8f, 0)
            tracker.observe("sad", 0.8f, 2_000)
            assertEquals(0, tracker.observe(label, 0.8f, 4_000))
            assertEquals(0, tracker.observe("sad", 0.8f, 6_000))
        }
    }

    @Test fun weakPredictionDoesNotCountAsContinuity() {
        val tracker = NegativeEmotionDurationTracker()
        tracker.observe("sad", 0.8f, 0)
        tracker.observe("sad", 0.39f, 2_000)
        assertEquals(0, tracker.observe("sad", 0.8f, 4_000))
    }

    @Test fun longGapAndClockResetRestartTheTimer() {
        val tracker = NegativeEmotionDurationTracker()
        tracker.observe("sad", 0.8f, 0)
        assertEquals(0, tracker.observe("sad", 0.8f, 7_000))
        assertEquals(0, tracker.observe("sad", 0.8f, 1_000))
    }
}
