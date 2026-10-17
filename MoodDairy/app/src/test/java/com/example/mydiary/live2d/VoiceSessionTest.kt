package com.example.mydiary.live2d

import org.junit.Assert.*
import org.junit.Test

class VoiceSessionTest {
    @Test fun noSpeechReturnsToStandbyAfterTwentySeconds() {
        val window = VoiceCaptureWindow(1000)
        assertEquals(VoiceCaptureWindow.Outcome.CONTINUE, window.sample(20999, false))
        assertEquals(VoiceCaptureWindow.Outcome.IDLE, window.sample(21000, false))
    }
    @Test fun pausesShorterThanOneAndHalfSecondsDoNotCutOffSlowSpeech() {
        val window = VoiceCaptureWindow(0)
        window.sample(500, true)
        assertEquals(VoiceCaptureWindow.Outcome.CONTINUE, window.sample(1999, false))
        window.sample(2000, true)
        assertEquals(VoiceCaptureWindow.Outcome.CONTINUE, window.sample(3499, false))
        assertEquals(VoiceCaptureWindow.Outcome.SUBMIT, window.sample(3500, false))
    }
    @Test fun utteranceLimitStartsWhenSpeechBeginsNotWhenMicrophoneOpens() {
        val window = VoiceCaptureWindow(0)
        assertEquals(VoiceCaptureWindow.Outcome.CONTINUE, window.sample(19000, true))
        for (time in 20000L..33000L step 1000) assertEquals(VoiceCaptureWindow.Outcome.CONTINUE, window.sample(time, true))
        assertEquals(VoiceCaptureWindow.Outcome.SUBMIT, window.sample(34000, true))
    }
    @Test fun repeatedWakeWordDoesNotBecomeAChatMessage() {
        assertEquals("", VoiceSessionText.stripWake("然然，然然。"))
        assertEquals("今天天气怎么样", VoiceSessionText.stripWake("然然，然然，今天天气怎么样"))
        assertEquals("你好", VoiceSessionText.stripWake("然然然然你好"))
        assertEquals("帮我写日记", VoiceSessionText.stripWake("然然 然然，帮我写日记"))
        assertEquals("我叫然然的朋友", VoiceSessionText.stripWake("我叫然然的朋友"))
    }
    @Test fun onlyExplicitEndCommandsEndTheConversation() {
        listOf("结束聊天", "先这样。", "然然，再见！").forEach { assertTrue(VoiceSessionText.endsConversation(it)) }
        assertFalse(VoiceSessionText.endsConversation("这首歌叫再见"))
    }
    @Test fun leavingForegroundOrCancellingDropsOldCallbacks() {
        val gate = VoiceCallbackGate()
        val before = gate.current()
        assertTrue(gate.accepts(before, true))
        assertFalse(gate.accepts(before, false))
        gate.invalidate()
        assertFalse(gate.accepts(before, true))
        assertTrue(gate.accepts(gate.current(), true))
    }
}
