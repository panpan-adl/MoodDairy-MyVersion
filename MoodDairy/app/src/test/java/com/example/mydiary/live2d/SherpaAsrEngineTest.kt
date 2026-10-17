package com.example.mydiary.live2d

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.SpeechRecognizer
import io.mockk.*
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.Assert.*

class SherpaAsrEngineTest {
    private val context = mockk<Context>()
    private val callback = mockk<SherpaAsrEngine.Callback>(relaxed = true)
    private val listeners = mutableListOf<RecognitionListener>()
    private val timers = mutableListOf<Runnable>()
    private lateinit var engine: SherpaAsrEngine
    private lateinit var first: SpeechRecognizer

    @Before fun prepare() {
        mockkStatic(SpeechRecognizer::class, Looper::class)
        mockkConstructor(Handler::class, Intent::class)
        every { Looper.getMainLooper() } returns mockk()
        every { SpeechRecognizer.isRecognitionAvailable(context) } returns true
        every { anyConstructed<Handler>().postDelayed(capture(timers), any()) } returns true
        every { anyConstructed<Handler>().removeCallbacks(any()) } just Runs
        every { anyConstructed<Intent>().putExtra(any(), any<String>()) } returns mockk()
        every { anyConstructed<Intent>().putExtra(any(), any<Boolean>()) } returns mockk()
        every { anyConstructed<Intent>().putExtra(any(), any<Int>()) } returns mockk()
        first = recognizer()
        every { SpeechRecognizer.createSpeechRecognizer(context) } returns first
        engine = SherpaAsrEngine(context)
        engine.setCallback(callback)
    }

    private fun recognizer(): SpeechRecognizer = mockk<SpeechRecognizer>(relaxed = true).also { sr ->
        every { sr.setRecognitionListener(capture(listeners)) } just Runs
    }

    @After fun clean() { unmockkAll() }

    @Test fun finishWaitsForFinalResultInsteadOfDestroyingRecordedSpeech() {
        assertTrue(engine.start())
        engine.finish()
        verify(exactly = 1) { first.stopListening() }
        verify(exactly = 0) { first.destroy() }
        val result = mockk<Bundle>()
        every { result.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION) } returns arrayListOf("今天很开心")
        listeners.single().onResults(result)
        verify(exactly = 1) { callback.onSegment("今天很开心") }
        verify(exactly = 1) { first.destroy() }
        timers.single().run()
        verify(exactly = 0) { callback.onError(any()) }
    }

    @Test fun cancelledSessionsCannotDestroyOrCompleteTheNextSession() {
        engine.start(); engine.finish()
        val oldListener = listeners.single()
        val oldTimeout = timers.single()
        engine.stop()
        val second = recognizer()
        every { SpeechRecognizer.createSpeechRecognizer(context) } returns second
        engine.start()
        oldListener.onError(SpeechRecognizer.ERROR_CLIENT)
        oldListener.onResults(null)
        oldTimeout.run()
        verify(exactly = 0) { second.destroy() }
        verify(exactly = 0) { callback.onError(any()) }
        verify(exactly = 0) { callback.onSegment(any()) }
    }

    @Test fun emptyResultsAreNotSentToChatAndProviderTimeoutRecovers() {
        engine.start()
        listeners.last().onResults(null)
        verify(exactly = 0) { callback.onSegment(any()) }
        verify(exactly = 1) { callback.onError(match { it.contains("有效语音") }) }
        engine.start(); engine.finish()
        timers.last().run()
        verify(exactly = 1) { callback.onError(match { it.contains("未返回结果") }) }
    }

    @Test fun failedStartReturnsFalseAndReportsTheRealCause() {
        every { first.startListening(any()) } throws SecurityException()
        assertFalse(engine.start())
        verify { callback.onError(match { it.contains("麦克风权限") }) }
        verify { first.destroy() }
    }
}
