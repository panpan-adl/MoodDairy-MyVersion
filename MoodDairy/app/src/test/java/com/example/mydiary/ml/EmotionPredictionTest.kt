package com.example.mydiary.ml

import org.junit.Assert.*
import org.junit.Test

class EmotionPredictionTest {
    @Test fun `author label order is preserved`() {
        val expected = listOf("angry", "disgust", "fear", "happy", "neutral", "sad", "surprise")
        expected.forEachIndexed { index, label ->
            val values = FloatArray(7) { if (it == index) 10f else -10f }
            assertEquals(label, EmotionPrediction.fromLogits(values)!!.first)
        }
    }
    @Test fun `large logits produce stable probability`() {
        val result = EmotionPrediction.fromLogits(floatArrayOf(10000f, 10000f, 10000f, 10000f, 10000f, 10000f, 10000f))!!
        assertEquals(1f / 7f, result.second, 1e-6f)
    }
    @Test fun `invalid output is rejected`() {
        assertNull(EmotionPrediction.fromLogits(FloatArray(6)))
        assertNull(EmotionPrediction.fromLogits(FloatArray(7) { Float.NaN }))
        assertNull(EmotionPrediction.fromLogits(FloatArray(7) { Float.POSITIVE_INFINITY }))
    }
    @Test fun `RGB normalization uses separate channel planes`() {
        val result = FaceImagePreprocessor.pixelsToRgbNchw(intArrayOf(0xffff0000.toInt(), 0xff00ff00.toInt(), 0xff0000ff.toInt()), 3, 1)
        val expected = floatArrayOf(0.515f / .229f, -.485f / .229f, -.485f / .229f,
            -.456f / .224f, .544f / .224f, -.456f / .224f,
            -.406f / .225f, -.406f / .225f, .594f / .225f)
        assertArrayEquals(expected, result, 1e-6f)
    }
}
