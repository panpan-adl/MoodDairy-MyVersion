package com.example.mydiary.ml

import kotlin.math.abs

/** Per-capture-session smile evidence; decision scores require camera calibration. */
class PositiveEmotionRefiner {
    private var lastSmileAt: Long? = null
    private var previousSmile: Float? = null
    private val smileWindow = ArrayDeque<Float>(3)

    fun reset() {
        lastSmileAt = null
        previousSmile = null
        smileWindow.clear()
    }

    fun refine(scores: FloatArray, smile: Float?, yaw: Float, pitch: Float, nowMs: Long = 0L): Pair<String, Float>? {
        val prediction = EmotionPrediction.fromProbabilities(scores) ?: run { reset(); return null }
        if (smile == null || !smile.isFinite() || smile !in 0f..1f ||
            !yaw.isFinite() || !pitch.isFinite() || abs(yaw) > 18f || abs(pitch) > 20f
        ) { reset(); return prediction }
        val previousAt = lastSmileAt
        val continuous = previousAt != null && nowMs > previousAt && nowMs - previousAt <= 6_000L
        val sustainedSmile = if (continuous) {
            minOf(previousSmile ?: 0f, smile)
        } else 0f
        if (!continuous) smileWindow.clear()
        if (smileWindow.size == 3) smileWindow.removeFirst()
        smileWindow.addLast(smile)
        val persistentSmile = if (smileWindow.size == 3) smileWindow.min() else 0f
        lastSmileAt = nowMs
        previousSmile = smile
        val happy = scores[EmotionModelConfig.LABELS.indexOf("happy")]
        val gap = prediction.second - happy
        // FER+ static-image neutral scores can dominate visible smiles. Require two
        // consecutive frontal observations before relaxing the model's happy gate.
        val sustainedNeutralSmile = prediction.first == "neutral" &&
            (sustainedSmile >= 0.90f ||
                (sustainedSmile >= 0.65f && happy >= 0.10f && gap <= 0.65f))
        // Disgust conflicts with visible smiles in camera use. Strong, persistent
        // smile evidence can resolve this specific conflict even when happy is low.
        // Moderate smiles still need model support. Other clear negatives are kept.
        val persistentDisgustSmile = prediction.first == "disgust" &&
            (persistentSmile >= 0.90f ||
                (persistentSmile >= 0.75f && happy >= 0.10f && gap <= 0.60f))
        val ambiguousNeutral = prediction.first == "neutral" && smile >= 0.90f &&
            happy >= 0.15f && gap <= 0.35f
        val ambiguousNegative = prediction.first in setOf("angry", "disgust", "fear", "sad") &&
            prediction.second < EmotionModelConfig.CONFIDENCE_THRESHOLD && smile >= 0.95f &&
            happy >= 0.20f && gap <= 0.15f
        // These are fused decision scores, not calibrated emotional certainty.
        return if (persistentDisgustSmile) {
            "happy" to persistentSmile
        } else if (sustainedNeutralSmile) {
            "happy" to sustainedSmile
        } else if (ambiguousNeutral || ambiguousNegative) {
            // This is a fused decision score, not an experimentally calibrated accuracy.
            "happy" to ((happy + smile) / 2f)
        } else prediction
    }
}
