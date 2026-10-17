package com.example.mydiary.ui.chat

/** A camera observation is a suggestion, never a replacement for a self-report. */
class MoodChangeTracker {
    private var candidate: String? = null
    private var since = 0L
    private var lastAt: Long? = null
    private var samples = 0
    private var quietUntil = 0L

    fun reset() { candidate = null; lastAt = null; samples = 0 }
    fun snooze(now: Long) { reset(); quietUntil = now + 300_000L }
    fun restart() { reset(); quietUntil = 0L }

    fun observe(selected: String?, label: String?, confidence: Float, now: Long): String? {
        val mood = normalize(label)
        if (selected == null || mood == null || mood == normalize(selected) ||
            !confidence.isFinite() || confidence !in .65f..1f || now < quietUntil) {
            reset(); return null
        }
        val previous = lastAt
        if (candidate != mood || previous == null || now <= previous || now - previous > 6_000L) {
            candidate = mood; since = now; samples = 0
        }
        lastAt = now
        samples++
        return mood.takeIf { samples >= 3 && now - since >= 6_000L }
    }

    companion object {
        val choices = listOf("happy", "neutral", "sad", "angry", "fear", "disgust", "surprise")
        fun normalize(label: String?): String? = when (label) {
            "smile", "broad_smile" -> "happy"
            "delighted_surprise" -> "surprise"
            in choices -> label
            else -> null
        }
    }
}
