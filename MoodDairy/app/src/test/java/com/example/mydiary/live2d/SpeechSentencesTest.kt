package com.example.mydiary.live2d
import org.junit.Assert.*
import org.junit.Test

class SpeechSentencesTest {
    @Test fun streamsCompleteSentencesWithoutRepeatingTail() {
        val sentences = SpeechSentences()
        assertTrue(sentences.append("今天").isEmpty())
        assertEquals(listOf("今天好吗？", "我在。"), sentences.append("好吗？我在。还有"))
        assertEquals(listOf("还有"), sentences.finish())
        assertTrue(sentences.finish().isEmpty())
    }
    @Test fun unpunctuatedLongAnswersStayWithinTtsLimits() {
        val sentences = SpeechSentences()
        val source = "你".repeat(1000)
        val parts = sentences.append(source) + sentences.finish()
        assertTrue(parts.all { it.length <= 180 })
        assertEquals(source, parts.joinToString(""))
    }
}
