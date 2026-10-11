package com.example.mydiary.ml

import kotlin.math.abs

/** Observable expression subtypes, not additional classes trained into the seven-class model. */
object PositiveExpressionClassifier {
    val names = mapOf("smile" to "微笑", "broad_smile" to "明显笑容", "delighted_surprise" to "惊喜表情")

    fun classify(prediction: Pair<String, Float>, scores: FloatArray, smile: Float?, yaw: Float, pitch: Float): String? {
        if (prediction.second < EmotionModelConfig.CONFIDENCE_THRESHOLD ||
            smile == null || !smile.isFinite() || smile !in 0f..1f ||
            !yaw.isFinite() || !pitch.isFinite() || abs(yaw) > 18f || abs(pitch) > 20f
        ) return null
        return when {
            prediction.first == "happy" && smile >= 0.95f -> "broad_smile"
            prediction.first == "happy" && smile >= 0.55f -> "smile"
            prediction.first == "surprise" && smile >= 0.85f &&
                scores.size == EmotionModelConfig.LABELS.size && scores[3] >= 0.15f -> "delighted_surprise"
            else -> null
        }
    }

    fun baseEmotion(label: String): String = when (label) {
        "smile", "broad_smile" -> "happy"
        "delighted_surprise" -> "surprise"
        else -> label
    }
}
