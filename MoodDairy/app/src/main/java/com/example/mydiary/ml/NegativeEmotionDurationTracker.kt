package com.example.mydiary.ml

/** Only uninterrupted, confident observations count; gaps and missing faces reset the timer. */
class NegativeEmotionDurationTracker(private val maxGapMs: Long = 6_000L) {
    private var startedAt: Long? = null
    private var lastAt: Long? = null

    fun observe(label: String?, confidence: Float, nowMs: Long): Long {
        if (label !in setOf("sad", "angry", "fear", "disgust") ||
            confidence < EmotionModelConfig.CONFIDENCE_THRESHOLD
        ) {
            reset()
            return 0L
        }
        val previous = lastAt
        if (previous == null || nowMs < previous || nowMs - previous > maxGapMs) {
            startedAt = nowMs
        }
        lastAt = nowMs
        return nowMs - (startedAt ?: nowMs)
    }

    fun reset() {
        startedAt = null
        lastAt = null
    }
}
