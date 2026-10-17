package com.example.mydiary.ui.chat

import org.junit.Assert.*
import org.junit.Test

class MoodChangeTrackerTest {
    @Test fun stableChangeSuggestsWithoutChangingSelfReport() {
        val tracker = MoodChangeTracker()
        for (time in listOf(1000L, 3000L, 5000L)) assertNull(tracker.observe("happy", "sad", .8f, time))
        assertEquals("sad", tracker.observe("happy", "sad", .8f, 7000L))
    }

    @Test fun equivalentSmilesAndAutomaticModeDoNotPrompt() {
        val tracker = MoodChangeTracker()
        for (time in 1000L..15000L step 2000L) {
            assertNull(tracker.observe("happy", "smile", .99f, time))
            assertNull(tracker.observe("happy", "broad_smile", .99f, time))
            assertNull(tracker.observe(null, "sad", .99f, time))
        }
    }

    @Test fun interruptionsResetEvidence() {
        for (interruption in listOf(null, "face_turned", "happy", "fear")) {
            val tracker = MoodChangeTracker()
            tracker.observe("happy", "sad", .9f, 1000L)
            tracker.observe("happy", "sad", .9f, 3000L)
            tracker.observe("happy", interruption, .9f, 5000L)
            assertNull(tracker.observe("happy", "sad", .9f, 7000L))
        }
        for (confidence in listOf(.64f, Float.NaN, 1.2f)) {
            val tracker = MoodChangeTracker()
            tracker.observe("happy", "sad", .9f, 1000L)
            tracker.observe("happy", "sad", confidence, 3000L)
            assertNull(tracker.observe("happy", "sad", .9f, 7000L))
        }
    }

    @Test fun gapsAndClockReversalCannotCountAsContinuity() {
        val tracker = MoodChangeTracker()
        tracker.observe("happy", "sad", .9f, 1000L)
        tracker.observe("happy", "sad", .9f, 3000L)
        assertNull(tracker.observe("happy", "sad", .9f, 10000L))
        assertNull(tracker.observe("happy", "sad", .9f, 2000L))
    }

    @Test fun decisionSnoozesThenRequiresFreshEvidence() {
        val tracker = MoodChangeTracker()
        tracker.snooze(1000L)
        assertNull(tracker.observe("happy", "sad", .9f, 300000L))
        assertNull(tracker.observe("happy", "sad", .9f, 301000L))
        assertNull(tracker.observe("happy", "sad", .9f, 303000L))
        assertNull(tracker.observe("happy", "sad", .9f, 305000L))
        assertEquals("sad", tracker.observe("happy", "sad", .9f, 307000L))
        tracker.restart()
        assertNull(tracker.observe("neutral", "sad", .9f, 309000L))
    }
}
