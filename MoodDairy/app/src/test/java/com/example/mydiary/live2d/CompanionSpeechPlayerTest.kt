package com.example.mydiary.live2d

import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.example.mydiary.data.network.ApiEndpoints
import io.mockk.*
import org.junit.After
import org.junit.Before
import org.junit.Test

class CompanionSpeechPlayerTest {
    private val first = mockk<MediaPlayer>(relaxed = true)
    private val second = mockk<MediaPlayer>(relaxed = true)
    private lateinit var player: CompanionSpeechPlayer
    private val prepared = mutableMapOf<MediaPlayer, MediaPlayer.OnPreparedListener>()
    private val completed = mutableMapOf<MediaPlayer, MediaPlayer.OnCompletionListener>()
    private val errors = mutableMapOf<MediaPlayer, MediaPlayer.OnErrorListener>()
    @Before fun setup() {
        mockkStatic(Looper::class, SystemClock::class, Log::class)
        mockkConstructor(Handler::class)
        mockkObject(ApiEndpoints)
        every { ApiEndpoints.apiBaseUrl } returns "http://localhost/"
        every { Looper.getMainLooper() } returns mockk()
        every { SystemClock.elapsedRealtime() } returns 0
        every { Log.i(any(), any()) } returns 0
        every { anyConstructed<Handler>().postDelayed(any(), any()) } returns true
        every { anyConstructed<Handler>().removeCallbacks(any()) } just Runs
        listOf(first, second).forEach { media ->
            every { media.setOnPreparedListener(any()) } answers { prepared[media] = firstArg() }
            every { media.setOnCompletionListener(any()) } answers { completed[media] = firstArg() }
            every { media.setOnErrorListener(any()) } answers { errors[media] = firstArg() }
        }
        val media = ArrayDeque(listOf(first, second))
        player = CompanionSpeechPlayer(onBusy = {}, playerFactory = { media.removeFirst() })
    }
    @After fun cleanup() { unmockkAll() }
    @Test fun prefetchFinishingOutOfOrderStillPlaysInSentenceOrder() {
        player.enqueue("第一句。")
        player.enqueue("第二句。")
        prepared.getValue(second).onPrepared(second)
        verify(exactly = 0) { second.start() }
        prepared.getValue(first).onPrepared(first)
        verify(exactly = 1) { first.start() }
        completed.getValue(first).onCompletion(first)
        verify(exactly = 1) { second.start() }
    }
    @Test fun cancellingDropsLatePreparedCallbacks() {
        player.enqueue("旧回复。")
        player.clear()
        prepared.getValue(first).onPrepared(first)
        verify(exactly = 1) { first.release() }
        verify(exactly = 0) { first.start() }
    }
    @Test fun networkFailureUsesLocalSpeechBeforeStartingTheNextSentence() {
        var finishFallback: (() -> Unit)? = null
        val media = ArrayDeque(listOf(first, second))
        player = CompanionSpeechPlayer(onBusy = {}, playerFactory = { media.removeFirst() },
            fallback = { _, done -> finishFallback = done })
        player.enqueue("第一句。")
        player.enqueue("第二句。")
        prepared.getValue(second).onPrepared(second)
        errors.getValue(first).onError(first, 1, 0)
        verify(exactly = 0) { second.start() }
        requireNotNull(finishFallback).invoke()
        verify(exactly = 1) { second.start() }
    }
}
