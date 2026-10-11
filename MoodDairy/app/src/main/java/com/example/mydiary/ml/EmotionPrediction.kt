package com.example.mydiary.ml

import kotlin.math.exp

/** ONNX 输出为 logits，需稳定 softmax 后才可作为置信度。 */
object EmotionPrediction {
    fun fromLogits(logits: FloatArray): Pair<String, Float>? {
        return probabilities(logits)?.let { fromProbabilities(it) }
    }

    fun probabilities(logits: FloatArray): FloatArray? {
        if (logits.size != EmotionModelConfig.LABELS.size || logits.any { !it.isFinite() }) return null
        val index = logits.indices.maxByOrNull { logits[it] } ?: return null
        val max = logits[index].toDouble()
        val total = logits.sumOf { exp(it.toDouble() - max) }
        return FloatArray(logits.size) { (exp(logits[it].toDouble() - max) / total).toFloat() }
    }

    fun fromProbabilities(scores: FloatArray): Pair<String, Float>? {
        if (scores.size != EmotionModelConfig.LABELS.size || scores.any { !it.isFinite() || it !in 0f..1f }) return null
        val index = scores.indices.maxByOrNull { scores[it] } ?: return null
        return EmotionModelConfig.LABELS[index] to scores[index]
    }
}
