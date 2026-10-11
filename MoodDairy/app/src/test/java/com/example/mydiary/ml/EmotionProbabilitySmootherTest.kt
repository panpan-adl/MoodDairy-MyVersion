package com.example.mydiary.ml

import org.junit.Assert.*
import org.junit.Test

class EmotionProbabilitySmootherTest {
    private fun scores(vararg entries: Pair<String, Float>): FloatArray =
        FloatArray(7).also { a -> entries.forEach { a[EmotionModelConfig.LABELS.indexOf(it.first)] = it.second } }

    @Test fun secondPlaceSadnessEvidenceIsNotLostByVoting() {
        val s = EmotionProbabilitySmoother()
        assertNull(s.add(scores("neutral" to .51f, "sad" to .49f), 0))
        assertNull(s.add(scores("angry" to .51f, "sad" to .49f), 2000))
        val result = s.add(scores("fear" to .51f, "sad" to .49f), 4000)!!
        assertEquals("sad", EmotionPrediction.fromProbabilities(result)!!.first)
        assertEquals(.49f, result[5], .0001f)
    }

    @Test fun newNeutralWindowDoesNotKeepOldAnger() {
        val s = EmotionProbabilitySmoother()
        repeat(3) { s.add(scores("angry" to 1f), it * 2000L) }
        var result: FloatArray? = null
        repeat(3) { result = s.add(scores("neutral" to 1f), 6000L + it * 2000) }
        assertEquals("neutral", EmotionPrediction.fromProbabilities(result!!)!!.first)
    }

    @Test fun gapsInvalidScoresAndResetDiscardHistory() {
        val s = EmotionProbabilitySmoother()
        repeat(3) { s.add(scores("sad" to 1f), it * 2000L) }
        assertNull(s.add(scores("sad" to 1f), 11000))
        assertNull(s.add(FloatArray(7) { Float.NaN }, 12000))
        assertNull(s.add(scores("sad" to 1f), 13000))
        s.reset()
        assertNull(s.add(scores("sad" to 1f), 14000))
    }

    @Test fun callerMutationDoesNotAlterHistory() {
        val s = EmotionProbabilitySmoother()
        val a = scores("sad" to 1f)
        s.add(a, 0); a.fill(0f)
        s.add(scores("sad" to 1f), 2000)
        assertEquals(1f, s.add(scores("sad" to 1f), 4000)!![5], .0001f)
    }

    @Test fun flipPreservesChannelsAndIsReversible() {
        val original = FloatArray(18) { it.toFloat() }
        val flipped = FaceImagePreprocessor.flipRgbNchw(original, 3, 2)
        assertArrayEquals(floatArrayOf(2f,1f,0f,5f,4f,3f,8f,7f,6f,11f,10f,9f,14f,13f,12f,17f,16f,15f), flipped, 0f)
        assertArrayEquals(original, FaceImagePreprocessor.flipRgbNchw(flipped, 3, 2), 0f)
    }
}
