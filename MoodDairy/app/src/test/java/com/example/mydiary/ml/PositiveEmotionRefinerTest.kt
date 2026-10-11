package com.example.mydiary.ml

import org.junit.Assert.*
import org.junit.Test

class PositiveEmotionRefinerTest {
    private val ambiguous = floatArrayOf(0.06f, 0.06f, 0.05f, 0.25f, 0.50f, 0.04f, 0.04f)

    @Test fun strongFrontalSmileResolvesHappyNeutralAmbiguity() {
        val result = PositiveEmotionRefiner.refine(ambiguous, 0.95f, 0f, 0f)!!
        assertEquals("happy", result.first)
        assertEquals(0.60f, result.second, 0.001f)
    }

    @Test fun mildSmileAndMissingSmileKeepModelDecision() {
        for (smile in listOf(null, 0.6f, Float.NaN, 1.1f)) {
            assertEquals("neutral", PositiveEmotionRefiner.refine(ambiguous, smile, 0f, 0f)!!.first)
        }
    }

    @Test fun turnedOrTiltedFaceDoesNotUseSmileAssistance() {
        assertEquals("neutral", PositiveEmotionRefiner.refine(ambiguous, 0.99f, 19f, 0f)!!.first)
        assertEquals("neutral", PositiveEmotionRefiner.refine(ambiguous, 0.99f, 0f, 21f)!!.first)
    }

    @Test fun clearNegativeAndClearNeutralCannotBeOverridden() {
        val negative = floatArrayOf(0.05f, 0.55f, 0.05f, 0.25f, 0.04f, 0.03f, 0.03f)
        assertEquals("disgust", PositiveEmotionRefiner.refine(negative, 0.99f, 0f, 0f)!!.first)
        val neutral = floatArrayOf(0.02f, 0.02f, 0.02f, 0.05f, 0.85f, 0.02f, 0.02f)
        assertEquals("neutral", PositiveEmotionRefiner.refine(neutral, 0.99f, 0f, 0f)!!.first)
    }

    @Test fun weakNegativeNeedsModelHappyEvidenceAndVeryStrongSmile() {
        val weak = floatArrayOf(0.10f, 0.34f, 0.05f, 0.25f, 0.15f, 0.06f, 0.05f)
        assertEquals("happy", PositiveEmotionRefiner.refine(weak, 0.98f, 0f, 0f)!!.first)
        assertEquals("disgust", PositiveEmotionRefiner.refine(weak, 0.90f, 0f, 0f)!!.first)
    }

    @Test fun oneSmilingFrameCannotSwitchStableNegative() {
        val smoother = EmotionSmoother()
        repeat(3) { smoother.addSample("sad", 0.8f) }
        val positive = PositiveEmotionRefiner.refine(ambiguous, 0.95f, 0f, 0f)!!
        assertEquals("sad", smoother.addPrediction(positive.first, positive.second)!!.first)
        assertEquals("happy", smoother.addPrediction(positive.first, positive.second)!!.first)
    }
}
