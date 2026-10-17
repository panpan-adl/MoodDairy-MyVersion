package com.example.mydiary.live2d

import io.mockk.*
import java.io.EOFException
import java.io.IOException
import java.net.SocketTimeoutException
import okhttp3.*
import okhttp3.Call
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.BufferedSource
import org.junit.Assert.*
import org.junit.Test

class VoiceUploadRecoveryInterceptorTest {
    private val request = Request.Builder().url("http://localhost/voice/companion-transcribe")
        .header("Authorization", "Bearer test-only").post("pcm".toRequestBody()).build()
    private val call = mockk<Call> { every { isCanceled() } returns false }
    private val chain = mockk<Interceptor.Chain> {
        every { request() } returns request
        every { call() } returns call
    }
    private fun response(code: Int = 200, body: ResponseBody = "{\"text\":\"你好\"}".toResponseBody()) =
        Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(code).message("test").body(body).build()

    @Test fun disconnectedUploadRetriesSameAuthenticatedAudioOnce() {
        every { chain.proceed(request) } throws IOException("unexpected end of stream") andThen response()
        VoiceUploadRecoveryInterceptor().intercept(chain).use { assertEquals("{\"text\":\"你好\"}", it.body!!.string()) }
        verify(exactly = 2) { chain.proceed(request) }
    }

    @Test fun secondFailureIsReturnedWithoutRetryLoop() {
        every { chain.proceed(request) } throws EOFException()
        assertThrows(EOFException::class.java) { VoiceUploadRecoveryInterceptor().intercept(chain) }
        verify(exactly = 2) { chain.proceed(request) }
    }

    @Test fun cancellationAndTimeoutNeverReplayAudio() {
        every { chain.proceed(request) } throws EOFException()
        every { call.isCanceled() } returns true
        assertThrows(EOFException::class.java) { VoiceUploadRecoveryInterceptor().intercept(chain) }
        verify(exactly = 1) { chain.proceed(request) }
        clearMocks(chain, answers = false, recordedCalls = true)
        every { call.isCanceled() } returns false
        every { chain.proceed(request) } throws SocketTimeoutException()
        assertThrows(SocketTimeoutException::class.java) { VoiceUploadRecoveryInterceptor().intercept(chain) }
        verify(exactly = 1) { chain.proceed(request) }
    }

    @Test fun responseBodyDisconnectClosesBeforeRetry() {
        val source = mockk<BufferedSource>(relaxed = true)
        every { source.readByteArray() } throws EOFException()
        val broken = object : ResponseBody() {
            override fun contentType(): MediaType? = null
            override fun contentLength() = -1L
            override fun source() = source
        }
        every { chain.proceed(request) } returns response(body = broken) andThen response()
        VoiceUploadRecoveryInterceptor().intercept(chain).close()
        verify(atLeast = 1) { source.close() }
        verify(exactly = 2) { chain.proceed(request) }
    }

    @Test fun httpErrorsDoNotReplay() {
        for (code in listOf(401, 403, 400, 503, 504)) {
            clearMocks(chain, answers = false, recordedCalls = true)
            every { chain.proceed(request) } returns response(code)
            VoiceUploadRecoveryInterceptor().intercept(chain).use { assertEquals(code, it.code) }
            verify(exactly = 1) { chain.proceed(request) }
        }
    }

    @Test fun otherWritesAreNeverRetried() {
        val chat = request.newBuilder().url("http://localhost/chat").build()
        every { chain.request() } returns chat
        every { chain.proceed(chat) } throws EOFException()
        assertThrows(EOFException::class.java) { VoiceUploadRecoveryInterceptor().intercept(chain) }
        verify(exactly = 1) { chain.proceed(chat) }
    }

    @Test fun errorsDistinguishTimeoutAuthenticationAndServiceFailure() {
        assertTrue(VoiceRecognitionErrors.connection(SocketTimeoutException()).contains("超时"))
        assertTrue(VoiceRecognitionErrors.connection(EOFException()).contains("连接已中断"))
        assertTrue(VoiceRecognitionErrors.response(401).contains("登录"))
        assertTrue(VoiceRecognitionErrors.response(504).contains("超时"))
        assertTrue(VoiceRecognitionErrors.response(503).contains("服务暂不可用"))
    }
}
