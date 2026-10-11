package com.example.mydiary.ml

import kotlin.math.abs

/** Conservative smile assistance for ambiguous model predictions; thresholds need field calibration. */
object PositiveEmotionRefiner {
    fun refine(scores: FloatArray, smile: Float?, yaw: Float, pitch: Float): Pair<String, Float>? {
        val prediction = EmotionPrediction.fromProbabilities(scores) ?: return null
        if (smile == null || !smile.isFinite() || smile !in 0f..1f ||
            !yaw.isFinite() || !pitch.isFinite() || abs(yaw) > 18f || abs(pitch) > 20f
        ) return prediction
        val happy = scores[EmotionModelConfig.LABELS.indexOf("happy")]
        val gap = prediction.second - happy
        // A smile cannot replace a clear negative prediction or an unrelated surprise.
        val ambiguousNeutral = prediction.first == "neutral" && smile >= 0.90f &&
            happy >= 0.15f && gap <= 0.35f
        val ambiguousNegative = prediction.first in setOf("angry", "disgust", "fear", "sad") &&
            prediction.second < EmotionModelConfig.CONFIDENCE_THRESHOLD && smile >= 0.95f &&
            happy >= 0.20f && gap <= 0.15f
        return if (ambiguousNeutral || ambiguousNegative) {
            // This is a fused decision score, not an experimentally calibrated accuracy.
            "happy" to ((happy + smile) / 2f)
        } else prediction
    }
}
