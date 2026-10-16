package com.example.mydiary.ml

import org.junit.Assert.*
import org.junit.Test

class PositiveEmotionRefinerTest {
    private val refiner = PositiveEmotionRefiner()
    private val ambiguous = floatArrayOf(0.06f, 0.06f, 0.05f, 0.25f, 0.50f, 0.04f, 0.04f)

    @Test fun sustainedSmileCanResolveDominantNeutralWithoutStrongModelHappyScore() {
        val neutral = floatArrayOf(.02f, .02f, .02f, .05f, .85f, .02f, .02f)
        assertEquals("neutral", refiner.refine(neutral, .96f, 0f, 0f, 1000L)!!.first)
        val result = refiner.refine(neutral, .94f, 0f, 0f, 3000L)!!
        assertEquals("happy", result.first)
        assertEquals("smile", PositiveExpressionClassifier.classify(result, neutral, .94f, 0f, 0f))
    }

    @Test fun sustainedModerateSmileUsesSupportingHappyEvidence() {
        val neutral = floatArrayOf(.02f, .02f, .02f, .15f, .75f, .02f, .02f)
        assertEquals("neutral", refiner.refine(neutral, .72f, 0f, 0f, 1000L)!!.first)
        assertEquals("happy", refiner.refine(neutral, .70f, 0f, 0f, 3000L)!!.first)
        assertEquals("neutral", refiner.refine(neutral, .20f, 0f, 0f, 5000L)!!.first)
        assertEquals("neutral", refiner.refine(neutral, .72f, 0f, 0f, 7000L)!!.first)
    }

    @Test fun gapsMissingSignalsTurnedFacesAndResetDiscardSmileHistory() {
        val neutral = floatArrayOf(.02f, .02f, .02f, .05f, .85f, .02f, .02f)
        refiner.refine(neutral, .99f, 0f, 0f, 1000L)
        assertEquals("neutral", refiner.refine(neutral, .99f, 0f, 0f, 8000L)!!.first)
        refiner.refine(neutral, null, 0f, 0f, 10000L)
        assertEquals("neutral", refiner.refine(neutral, .99f, 0f, 0f, 12000L)!!.first)
        refiner.refine(neutral, .99f, 25f, 0f, 14000L)
        assertEquals("neutral", refiner.refine(neutral, .99f, 0f, 0f, 16000L)!!.first)
        refiner.reset()
        assertEquals("neutral", refiner.refine(neutral, .99f, 0f, 0f, 18000L)!!.first)
    }

    @Test fun sustainedSmilesDoNotOverrideOtherClearNegativeOrSurprise() {
        for (label in listOf("angry", "fear", "sad", "surprise")) {
            val scores = FloatArray(7) { .025f }
            scores[EmotionModelConfig.LABELS.indexOf(label)] = .85f
            refiner.reset()
            refiner.refine(scores, .99f, 0f, 0f, 1000L)
            assertEquals(label, refiner.refine(scores, .99f, 0f, 0f, 3000L)!!.first)
        }
    }

    @Test fun persistentStrongSmileCorrectsDisgustDespiteLowModelHappyScore() {
        val disgust = floatArrayOf(.02f, .85f, .02f, .05f, .02f, .02f, .02f)
        assertEquals("disgust", refiner.refine(disgust, .97f, 0f, 0f, 1000L)!!.first)
        assertEquals("disgust", refiner.refine(disgust, .96f, 0f, 0f, 3000L)!!.first)
        val result = refiner.refine(disgust, .95f, 0f, 0f, 5000L)!!
        assertEquals("happy", result.first)
        assertEquals("broad_smile", PositiveExpressionClassifier.classify(result, disgust, .95f, 0f, 0f))
        assertEquals("disgust", refiner.refine(disgust, .10f, 0f, 0f, 7000L)!!.first)
    }

    @Test fun persistentModerateSmileNeedsHappySupportToCorrectDisgust() {
        val disgust = floatArrayOf(.04f, .60f, .04f, .16f, .08f, .04f, .04f)
        refiner.refine(disgust, .82f, 0f, 0f, 1000L)
        refiner.refine(disgust, .80f, 0f, 0f, 3000L)
        val result = refiner.refine(disgust, .78f, 0f, 0f, 5000L)!!
        assertEquals("happy", result.first)
        assertEquals("smile", PositiveExpressionClassifier.classify(result, disgust, .78f, 0f, 0f))
    }

    @Test fun disgustWithoutReliableSmileIsPreserved() {
        val disgust = floatArrayOf(.02f, .85f, .02f, .05f, .02f, .02f, .02f)
        for (smile in listOf(null, .10f, .80f, Float.NaN)) {
            refiner.reset()
            for (time in listOf(1000L, 3000L, 5000L, 7000L)) {
                assertEquals("disgust", refiner.refine(disgust, smile, 0f, 0f, time)!!.first)
            }
        }
    }

    @Test fun interruptedSmileCannotAccumulateDisgustCorrection() {
        val disgust = floatArrayOf(.02f, .85f, .02f, .05f, .02f, .02f, .02f)
        for (interruption in listOf("missing", "turned", "gap", "reset", "weak", "reversed")) {
            refiner.reset()
            refiner.refine(disgust, .99f, 0f, 0f, 1000L)
            refiner.refine(disgust, .99f, 0f, 0f, 3000L)
            when (interruption) {
                "missing" -> refiner.refine(disgust, null, 0f, 0f, 4000L)
                "turned" -> refiner.refine(disgust, .99f, 25f, 0f, 4000L)
                "reset" -> refiner.reset()
                "weak" -> refiner.refine(disgust, .1f, 0f, 0f, 4000L)
            }
            val nextTime = when (interruption) { "gap" -> 10000L; "reversed" -> 2000L; else -> 5000L }
            assertEquals(interruption, "disgust", refiner.refine(disgust, .99f, 0f, 0f, nextTime)!!.first)
        }
    }

    @Test fun correctedSmileResetsNegativePromptTimer() {
        val disgust = floatArrayOf(.02f, .85f, .02f, .05f, .02f, .02f, .02f)
        val timer = NegativeEmotionDurationTracker()
        for (time in 1000L..15000L step 2000L) {
            val result = refiner.refine(disgust, .99f, 0f, 0f, time)!!
            assertTrue(timer.observe(result.first, result.second, time) < EmotionModelConfig.NEGATIVE_PROMPT_DURATION_MS)
        }
        assertEquals(0L, timer.observe("disgust", .85f, 17000L))
    }

    @Test fun strongFrontalSmileResolvesHappyNeutralAmbiguity() {
        val result = refiner.refine(ambiguous, 0.95f, 0f, 0f)!!
        assertEquals("happy", result.first)
        assertEquals(0.60f, result.second, 0.001f)
    }

    @Test fun mildSmileAndMissingSmileKeepModelDecision() {
        for (smile in listOf(null, 0.6f, Float.NaN, 1.1f)) {
            assertEquals("neutral", refiner.refine(ambiguous, smile, 0f, 0f)!!.first)
        }
    }

    @Test fun turnedOrTiltedFaceDoesNotUseSmileAssistance() {
        assertEquals("neutral", refiner.refine(ambiguous, 0.99f, 19f, 0f)!!.first)
        assertEquals("neutral", refiner.refine(ambiguous, 0.99f, 0f, 21f)!!.first)
    }

    @Test fun clearNegativeAndClearNeutralCannotBeOverridden() {
        val negative = floatArrayOf(0.05f, 0.55f, 0.05f, 0.25f, 0.04f, 0.03f, 0.03f)
        assertEquals("disgust", refiner.refine(negative, 0.99f, 0f, 0f)!!.first)
        val neutral = floatArrayOf(0.02f, 0.02f, 0.02f, 0.05f, 0.85f, 0.02f, 0.02f)
        assertEquals("neutral", refiner.refine(neutral, 0.99f, 0f, 0f)!!.first)
    }

    @Test fun weakNegativeNeedsModelHappyEvidenceAndVeryStrongSmile() {
        val weak = floatArrayOf(0.10f, 0.34f, 0.05f, 0.25f, 0.15f, 0.06f, 0.05f)
        assertEquals("happy", refiner.refine(weak, 0.98f, 0f, 0f)!!.first)
        assertEquals("disgust", refiner.refine(weak, 0.90f, 0f, 0f)!!.first)
    }

    @Test fun oneSmilingFrameCannotSwitchStableNegative() {
        val smoother = EmotionSmoother()
        repeat(3) { smoother.addSample("sad", 0.8f) }
        val positive = refiner.refine(ambiguous, 0.95f, 0f, 0f)!!
        assertEquals("sad", smoother.addPrediction(positive.first, positive.second)!!.first)
        assertEquals("happy", smoother.addPrediction(positive.first, positive.second)!!.first)
    }
}
