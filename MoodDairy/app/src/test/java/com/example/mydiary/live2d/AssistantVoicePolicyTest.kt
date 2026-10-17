package com.example.mydiary.live2d

import org.junit.Assert.*
import org.junit.Test

class AssistantVoicePolicyTest {
    private fun allowed(assistant: Boolean = true, showcase: Boolean = false, enabled: Boolean = true,
        resumed: Boolean = true, unlocked: Boolean = true, interactive: Boolean = true, microphone: Boolean = true) =
        AssistantVoicePolicy.available(assistant, showcase, enabled, resumed, unlocked, interactive, microphone)

    @Test fun onlyVisibleUnlockedAssistantCanOwnTheMicrophone() {
        assertTrue(allowed())
        assertFalse(allowed(assistant = false))
        assertFalse(allowed(showcase = true))
        assertFalse(allowed(enabled = false))
        assertFalse(allowed(resumed = false))
        assertFalse(allowed(unlocked = false))
        assertFalse(allowed(interactive = false))
        assertFalse(allowed(microphone = false))
    }

    @Test fun enteringAssistantWaitsWithoutAnExplicitTap() {
        assertEquals(AssistantVoicePolicy.Resume.NONE,
            AssistantVoicePolicy.resume(allowed(assistant = false), false, true, false))
        assertEquals(AssistantVoicePolicy.Resume.WAIT_FOR_WAKE,
            AssistantVoicePolicy.resume(allowed(), false, true, false))
    }

    @Test fun replyingAndSpeakingPauseListeningThenResumeTheConversation() {
        assertEquals(AssistantVoicePolicy.Resume.NONE,
            AssistantVoicePolicy.resume(allowed(), true, true, true))
        assertEquals(AssistantVoicePolicy.Resume.CONTINUE_CONVERSATION,
            AssistantVoicePolicy.resume(allowed(), false, true, true))
        assertEquals(AssistantVoicePolicy.Resume.WAIT_FOR_WAKE,
            AssistantVoicePolicy.resume(allowed(), false, true, false))
    }

    @Test fun disablingWakeNeverAutomaticallyReopensTheMicrophone() {
        for (continuous in listOf(true, false)) {
            assertEquals(AssistantVoicePolicy.Resume.NONE,
                AssistantVoicePolicy.resume(allowed(), false, false, continuous))
        }
    }

    @Test fun pageExitInvalidatesOldSpeechEvenAfterReturning() {
        val gate = VoiceCallbackGate()
        val previous = gate.current()
        assertTrue(gate.accepts(previous, allowed()))
        assertFalse(gate.accepts(previous, allowed(assistant = false)))
        gate.invalidate()
        assertFalse(gate.accepts(previous, allowed()))
        assertTrue(gate.accepts(gate.current(), allowed()))
    }

    @Test fun missingPermissionAndBackgroundPreventAutomaticResume() {
        for (available in listOf(allowed(microphone = false), allowed(resumed = false), allowed(unlocked = false))) {
            assertEquals(AssistantVoicePolicy.Resume.NONE,
                AssistantVoicePolicy.resume(available, false, true, true))
        }
    }
}
