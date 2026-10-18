package com.example.mydiary.ui.chat

import org.junit.Assert.*
import org.junit.Test

class ManualMoodCareTest {
    @Test fun everyNegativeSelfReportHasDistinctImmediateSuggestions() {
        val moods = listOf("sad", "angry", "fear", "disgust")
        val care = moods.map { ManualMoodCare.forMood(it)!! }
        assertEquals(moods, care.map { it.mood })
        assertEquals(4, care.map { it.tips }.distinct().size)
        assertTrue(care.all { it.introduction.isNotBlank() && it.tips.size == 3 && it.tips.all(String::isNotBlank) })
    }

    @Test fun automaticPositiveNeutralAndSurpriseDoNotTriggerCare() {
        for (mood in listOf(null, "happy", "neutral", "surprise", "smile", "delighted_surprise", "unknown")) {
            assertNull(ManualMoodCare.forMood(mood))
        }
    }
}
