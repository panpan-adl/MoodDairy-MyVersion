package com.example.mydiary.live2d

import android.content.Context
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.example.mydiary.data.network.ApiEndpoints
import io.mockk.*
import okhttp3.*
import okhttp3.Call
import org.junit.*
import org.junit.Assert.*
import org.junit.rules.TemporaryFolder
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import okhttp3.ResponseBody.Companion.toResponseBody
import java.util.concurrent.TimeUnit

class CompanionVoiceInputTest {
    @get:Rule val folder = TemporaryFolder()
    private val callbacks = mutableListOf<Callback>()
    private val timers = mutableListOf<Runnable>()
    private val callback = mockk<SherpaAsrEngine.Callback>(relaxed = true)
    private val call = mockk<Call>(relaxed = true)
    private val healthCall = mockk<Call>(relaxed = true)
    private val healthCallbacks = mutableListOf<Callback>()
    private lateinit var input: CompanionVoiceInput

    @Before fun prepare() {
        mockkStatic(Looper::class, SystemClock::class, android.util.Log::class)
        every { android.util.Log.i(any(), any()) } returns 0
        every { SystemClock.elapsedRealtime() } returns 0L
        mockkConstructor(Handler::class, MediaRecorder::class)
        mockkObject(ApiEndpoints)
        every { ApiEndpoints.apiBaseUrl } returns "http://localhost/"
        every { Looper.getMainLooper() } returns mockk()
        every { SystemClock.uptimeMillis() } returns 0L
        every { anyConstructed<Handler>().postDelayed(capture(timers), any()) } returns true
        every { anyConstructed<Handler>().removeCallbacks(any()) } just Runs
        every { anyConstructed<Handler>().post(any()) } answers { firstArg<Runnable>().run(); true }
        every { anyConstructed<MediaRecorder>().setAudioSource(any()) } just Runs
        every { anyConstructed<MediaRecorder>().setOutputFormat(any()) } just Runs
        every { anyConstructed<MediaRecorder>().setAudioEncoder(any()) } just Runs
        every { anyConstructed<MediaRecorder>().setAudioSamplingRate(any()) } just Runs
        every { anyConstructed<MediaRecorder>().setAudioEncodingBitRate(any()) } just Runs
        every { anyConstructed<MediaRecorder>().setOutputFile(any<String>()) } just Runs
        every { anyConstructed<MediaRecorder>().prepare() } just Runs
        every { anyConstructed<MediaRecorder>().start() } just Runs
        every { anyConstructed<MediaRecorder>().stop() } just Runs
        every { anyConstructed<MediaRecorder>().release() } just Runs
        every { anyConstructed<MediaRecorder>().maxAmplitude } returns 1000
        val context = mockk<Context>()
        every { context.cacheDir } returns folder.root
        val client = mockk<OkHttpClient>()
        val builder = mockk<OkHttpClient.Builder>()
        every { client.newBuilder() } returns builder
        every { builder.readTimeout(any<Long>(), any<TimeUnit>()) } returns builder
        every { builder.connectTimeout(any<Long>(), any<TimeUnit>()) } returns builder
        every { builder.callTimeout(any<Long>(), any<TimeUnit>()) } returns builder
        every { builder.retryOnConnectionFailure(false) } returns builder
        every { builder.build() } returns client
        every { client.newCall(any()) } returns call
        every { client.newCall(match { it.url.encodedPath == "/health" }) } returns healthCall
        every { call.enqueue(capture(callbacks)) } just Runs
        every { healthCall.enqueue(capture(healthCallbacks)) } just Runs
        input = CompanionVoiceInput(context, client, callback)
    }

    @After fun clean() { unmockkAll() }

    @Test fun finishingTwiceSubmitsOnlyOnceAndFailureDeletesTemporaryAudio() {
        assertTrue(input.start())
        assertEquals(1, folder.root.listFiles()!!.size)
        input.finish(); input.finish()
        verify(exactly = 1) { call.enqueue(any()) }
        verify(exactly = 1) { callback.onProcessing() }
        callbacks.single().onFailure(call, IOException("network"))
        healthCallbacks.single().onFailure(healthCall, ConnectException("refused"))
        assertEquals(0, folder.root.listFiles()!!.size)
        verify(exactly = 1) { callback.onError(any()) }
    }

    @Test fun cancellationDropsLateNetworkCallbacksAndDeletesAudio() {
        input.start(); input.finish(); input.stop()
        callbacks.single().onFailure(call, IOException("cancelled"))
        assertEquals(0, folder.root.listFiles()!!.size)
        verify { call.cancel() }
        verify(exactly = 0) { callback.onError(any()) }
        verify(exactly = 0) { callback.onSegment(any()) }
    }

    private fun response(body: String, status: Int = 200) = Response.Builder()
        .request(Request.Builder().url("http://localhost/voice/companion-transcribe").build())
        .protocol(Protocol.HTTP_1_1).code(status).message("test").body(body.toResponseBody()).build()

    @Test fun silenceNeverUploadsAndRemovesTemporaryRecording() {
        every { anyConstructed<MediaRecorder>().maxAmplitude } returns 0
        input.start(); input.finish()
        assertEquals(0, folder.root.listFiles()!!.size)
        assertTrue(callbacks.isEmpty())
        verify { callback.onError(match { it.contains("没有检测到声音") }) }
        verify(exactly = 0) { callback.onProcessing() }
    }

    @Test fun earlierVoiceSampleStillCountsWhenEndingInSilence() {
        every { anyConstructed<MediaRecorder>().maxAmplitude } returnsMany listOf(1000, 0)
        input.start(); timers.first().run(); input.finish()
        assertEquals(1, callbacks.size)
        callbacks.single().onResponse(call, response("""{"text":"你好"}"""))
        verify(exactly = 1) { callback.onSegment("你好") }
        assertEquals(0, folder.root.listFiles()!!.size)
    }

    @Test fun disconnectedBackendHasSpecificHint() {
        input.start(); input.finish()
        callbacks.single().onFailure(call, ConnectException("refused"))
        healthCallbacks.single().onFailure(healthCall, ConnectException("refused"))
        verify { callback.onError(match { it.contains("确认电脑后端已启动") }) }
    }

    @Test fun reachableBackendDoesNotResubmitFailedAudio() {
        input.start(); input.finish()
        callbacks.single().onFailure(call, IOException("reset"))
        healthCallbacks.single().onResponse(healthCall, response("{}"))
        verify { callback.onError(match { it.contains("上传连接中断") }) }
        verify(exactly = 1) { call.enqueue(any()) }
    }

    @Test fun timeoutDoesNotPretendSpeechWasEmpty() {
        input.start(); input.finish()
        callbacks.single().onFailure(call, SocketTimeoutException())
        verify { callback.onError(match { it.contains("超时") }) }
        assertTrue(healthCallbacks.isEmpty())
    }

    @Test fun emptyTranscriptionIsDifferentFromUnavailableService() {
        input.start(); input.finish()
        callbacks.single().onResponse(call, response("""{"text":""}"""))
        verify { callback.onError(match { it.contains("没有听清") }) }
    }

    @Test fun serviceUnavailableHasSpecificHint() {
        input.start(); input.finish()
        callbacks.single().onResponse(call, response("{}", 503))
        verify { callback.onError(match { it.contains("服务暂不可用") }) }
    }

    @Test fun cancellationAlsoCancelsHealthProbeAndDropsItsResult() {
        input.start(); input.finish()
        callbacks.single().onFailure(call, IOException("reset"))
        input.stop()
        healthCallbacks.single().onFailure(healthCall, IOException("cancelled"))
        verify { healthCall.cancel() }
        verify(exactly = 0) { callback.onError(any()) }
    }

    @Test fun malformedProviderResponseIsNotReportedAsSilence() {
        input.start(); input.finish()
        callbacks.single().onResponse(call, response("not json"))
        verify { callback.onError(match { it.contains("返回异常") }) }
    }
}
