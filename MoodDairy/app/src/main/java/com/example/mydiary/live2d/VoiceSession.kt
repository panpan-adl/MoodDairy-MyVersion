package com.example.mydiary.live2d

/** Platform-free timing/intent rules used by the single microphone owner. */
class VoiceCaptureWindow(private val beganAt: Long) {
    var heardSpeech = false
        private set
    private var speechBeganAt = beganAt
    private var lastSpeechAt = beganAt
    fun sample(now: Long, voiced: Boolean): Outcome {
        if (voiced) {
            if (!heardSpeech) speechBeganAt = now
            heardSpeech = true
            lastSpeechAt = now
        }
        return when {
            !heardSpeech && now - beganAt >= 20_000 -> Outcome.IDLE
            heardSpeech && (now - lastSpeechAt >= 1_500 || now - speechBeganAt >= 15_000) -> Outcome.SUBMIT
            else -> Outcome.CONTINUE
        }
    }
    enum class Outcome { CONTINUE, IDLE, SUBMIT }
}

object VoiceSessionText {
    fun stripWake(text: String): String = text.trim().replace(
        Regex("^(?:[，,。！!？?\\s]*然然)+[，,。！!？?\\s]*"), "").trim()
    fun endsConversation(text: String): Boolean = stripWake(text)
        .filterNot { it.isWhitespace() || it in "，,。！!？?～~" } in setOf("结束聊天", "先这样", "再见")
}

class VoiceCallbackGate {
    private val serial = java.util.concurrent.atomic.AtomicLong()
    fun current(): Long = serial.get()
    fun invalidate() { serial.incrementAndGet() }
    fun accepts(token: Long, foreground: Boolean): Boolean = foreground && token == current()
}

/** Shared by page transitions, automatic resume and microphone-worker callbacks. */
object AssistantVoicePolicy {
    fun available(assistant: Boolean, showcase: Boolean, enabled: Boolean,
        resumed: Boolean, unlocked: Boolean, interactive: Boolean, microphone: Boolean): Boolean =
        assistant && !showcase && enabled && resumed && unlocked && interactive && microphone

    enum class Resume { NONE, WAIT_FOR_WAKE, CONTINUE_CONVERSATION }
    fun resume(available: Boolean, busy: Boolean, wakeEnabled: Boolean, continuous: Boolean): Resume = when {
        !available || busy || !wakeEnabled -> Resume.NONE
        continuous -> Resume.CONTINUE_CONVERSATION
        else -> Resume.WAIT_FOR_WAKE
    }
}
