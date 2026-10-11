package com.example.mydiary.ml

/** Average complete distributions; never carry an old winning label through a new window. */
class EmotionProbabilitySmoother(private val windowSize: Int = 3, private val maxGapMs: Long = 6000L) {
    init { require(windowSize > 0 && maxGapMs > 0) }
    private val window = ArrayDeque<FloatArray>()
    private var lastAt: Long? = null
    fun reset() { window.clear(); lastAt = null }
    fun add(scores: FloatArray, nowMs: Long): FloatArray? {
        if (EmotionPrediction.fromProbabilities(scores) == null || kotlin.math.abs(scores.sum() - 1f) > 0.01f) {
            reset(); return null
        }
        lastAt?.let { if (nowMs < it || nowMs - it > maxGapMs) reset() }
        lastAt = nowMs
        if (window.size == windowSize) window.removeFirst()
        window.addLast(scores.copyOf())
        if (window.size < windowSize) return null
        return FloatArray(scores.size) { i -> window.sumOf { it[i].toDouble() }.toFloat() / window.size }
    }
}
