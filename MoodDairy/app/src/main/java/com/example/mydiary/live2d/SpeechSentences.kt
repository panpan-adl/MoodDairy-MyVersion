package com.example.mydiary.live2d

/** Collect complete spoken sentences while tokens arrive; never repeat final replies. */
class SpeechSentences {
    private val pending = StringBuilder()
    fun append(delta: String): List<String> {
        pending.append(delta)
        val result = mutableListOf<String>()
        while (true) {
            val end = pending.indexOfFirst { it in "。！？!?；;\n" }
            val split = when {
                end >= 0 && end < 180 -> end + 1
                pending.length >= 180 -> 180
                else -> break
            }
            pending.substring(0, split).trim().takeIf { it.isNotEmpty() }?.let(result::add)
            pending.delete(0, split)
        }
        return result
    }
    fun finish(): List<String> = pending.toString().trim().let {
        pending.clear()
        if (it.isEmpty()) emptyList() else listOf(it)
    }
}
