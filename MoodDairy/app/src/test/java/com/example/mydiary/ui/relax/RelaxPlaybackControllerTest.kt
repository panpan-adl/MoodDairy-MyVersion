package com.example.mydiary.ui.relax

import com.example.mydiary.data.models.*
import com.example.mydiary.data.network.DiaryApiService
import com.example.mydiary.whitenoise.WhiteNoiseSound
import androidx.compose.ui.graphics.Color
import io.mockk.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import retrofit2.Response

@OptIn(ExperimentalCoroutinesApi::class)
class RelaxPlaybackControllerTest {
    @Test fun floatingPositionStaysInsideSmallAndKeyboardReducedBounds() {
        assertEquals(0f, com.example.mydiary.music.clampFloatingPosition(-20f, 100f), .001f)
        assertEquals(100f, com.example.mydiary.music.clampFloatingPosition(250f, 100f), .001f)
        assertEquals(0f, com.example.mydiary.music.clampFloatingPosition(50f, -10f), .001f)
    }
    private class FakeEngine(val emit: (RelaxEngineEvent) -> Unit) : RelaxAudioEngine {
        override val positionMs = 1200L
        override val durationMs = 30000L
        var released = false
        var paused = false
        var gain = 0f
        var source = ""
        var looped = false
        override fun load(url: String, loop: Boolean) { source = url; looped = loop }
        override fun play() { paused = false; emit(RelaxEngineEvent(true)) }
        override fun pause() { paused = true; emit(RelaxEngineEvent(false)) }
        override fun seekTo(ms: Long) { }
        override fun setVolume(volume: Float) { gain = volume }
        override fun release() { released = true }
    }
    private fun song(id: String = "1") = RelaxTrack(id, "歌曲$id", "作者", "https://music.163.com/song?id=$id")
    private suspend fun TestScope.check(block: suspend TestScope.(RelaxPlaybackController, DiaryApiService, MutableList<FakeEngine>) -> Unit) = run {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val api = mockk<DiaryApiService>()
        val factory = mockk<RelaxEngineFactory>()
        val engines = mutableListOf<FakeEngine>()
        every { factory.resourceUri(any()) } answers { "resource://${firstArg<Int>()}" }
        every { factory.create(any()) } answers { FakeEngine(firstArg()).also { engines.add(it) } }
        coEvery { api.relaxPlayback(any()) } answers { Response.success(RelaxPlayback("https://audio.example/${firstArg<String>()}.mp3")) }
        val controller = RelaxPlaybackController(api, factory)
        try { block(controller, api, engines) } finally { controller.stop(); Dispatchers.resetMain() }
    }

    @Test fun returnsOnlyAfterRealPlaybackAndConsumesEventOnce() = runTest { check { c, _, e ->
        c.toggle(song()); runCurrent()
        assertNull(c.state.value.assistantRequest)
        e.last().emit(RelaxEngineEvent(false, true)); assertNull(c.state.value.assistantRequest)
        e.last().emit(RelaxEngineEvent(true))
        val token = c.state.value.assistantRequest!!
        assertTrue(c.consumeAssistantRequest(token)); assertFalse(c.consumeAssistantRequest(token))
        e.last().emit(RelaxEngineEvent(true)); assertNull(c.state.value.assistantRequest)
        assertEquals(.5f, e.last().gain, .001f)
    } }

    @Test fun switchingMusicAndNoiseReleasesPriorSourceAndIgnoresOldEvents() = runTest { check { c, _, e ->
        c.toggle(song()); runCurrent()
        val old = e.last()
        c.playNoise(WhiteNoiseSound("rain", "雨声", "RAIN", 1, "", Color.White))
        assertTrue(old.released); assertTrue(e.last().looped)
        old.emit(RelaxEngineEvent(true)); assertNull(c.state.value.assistantRequest)
        c.toggle(song("2")); runCurrent()
        assertTrue(e[1].released); assertFalse(c.state.value.whiteNoise); assertEquals(3, e.size)
    } }

    @Test fun quickSelectionAndStopIgnoreLateNetworkResult() = runTest { check { c, api, e ->
        val pending = CompletableDeferred<Response<RelaxPlayback>>()
        coEvery { api.relaxPlayback("1") } coAnswers { withContext(NonCancellable) { pending.await() } }
        c.toggle(song()); runCurrent(); c.toggle(song("2")); runCurrent()
        pending.complete(Response.success(RelaxPlayback("https://audio.example/old.mp3"))); runCurrent()
        assertEquals(1, e.size); assertTrue(e.last().source.endsWith("2.mp3"))
        c.stop(); e.last().emit(RelaxEngineEvent(true))
        assertNull(c.state.value.trackId); assertNull(c.state.value.assistantRequest)
    } }

    @Test fun errorsNeverNavigateAndRetryCanPlay() = runTest { check { c, api, e ->
        coEvery { api.relaxPlayback("1") } throws java.io.IOException()
        c.toggle(song()); runCurrent(); assertNotNull(c.state.value.error); assertNull(c.state.value.assistantRequest)
        coEvery { api.relaxPlayback("1") } returns Response.success(RelaxPlayback("https://audio.example/retry.mp3"))
        c.togglePause(); runCurrent(); e.last().emit(RelaxEngineEvent(false, error = "decode failed"))
        assertNull(c.state.value.assistantRequest); assertEquals("decode failed", c.state.value.error)
    } }

    @Test fun backgroundPauseKeepsSessionAndSpeechRestoresUserVolume() = runTest { check { c, _, e ->
        c.toggle(song()); runCurrent(); e.last().emit(RelaxEngineEvent(true))
        c.setVolume(.8f); c.setSpeechActive(true); assertEquals(.16f, e.last().gain, .001f)
        c.setSpeechActive(false); assertEquals(.8f, e.last().gain, .001f)
        c.pause(); assertTrue(e.last().paused); assertFalse(e.last().released)
        assertEquals("1", c.state.value.trackId); assertNull(c.state.value.assistantRequest)
        c.togglePause(); assertTrue(c.state.value.playing); assertNull(c.state.value.assistantRequest)
        c.stop(); assertTrue(e.last().released)
    } }

    @Test fun pendingRequestCannotAutoplayAfterBackgroundPause() = runTest { check { c, api, e ->
        val pending = CompletableDeferred<Response<RelaxPlayback>>()
        coEvery { api.relaxPlayback("1") } coAnswers { withContext(NonCancellable) { pending.await() } }
        c.toggle(song()); runCurrent(); c.pause()
        pending.complete(Response.success(RelaxPlayback("https://audio.example/late.mp3"))); runCurrent()
        assertTrue(e.isEmpty()); assertFalse(c.state.value.loading); assertNull(c.state.value.assistantRequest)
    } }

}
