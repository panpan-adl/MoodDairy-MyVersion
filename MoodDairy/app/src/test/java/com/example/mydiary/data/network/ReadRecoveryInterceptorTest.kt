package com.example.mydiary.data.network

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.io.EOFException
import java.net.SocketTimeoutException
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.junit.Assert.assertSame
import org.junit.Assert.fail
import org.junit.Test

class ReadRecoveryInterceptorTest {
    private fun chain(method: String = "GET", path: String = "/diaries/id/1"): Interceptor.Chain {
        val chain = mockk<Interceptor.Chain>()
        val request = Request.Builder().url("http://localhost:8000$path")
            .method(method, if (method in listOf("GET", "HEAD")) null else "{}".toRequestBody()).build()
        every { chain.request() } returns request
        every { chain.call().isCanceled() } returns false
        return chain
    }

    @Test fun interruptedReadRecoversOnce() {
        val chain = chain()
        val response = mockk<Response>()
        every { chain.proceed(any()) } throws EOFException("unexpected end of stream") andThen response
        assertSame(response, ReadRecoveryInterceptor().intercept(chain))
        verify(exactly = 2) { chain.proceed(any()) }
    }

    @Test fun persistentFailureStopsAfterOneRetry() {
        val chain = chain()
        val error = EOFException()
        every { chain.proceed(any()) } throws error
        try { ReadRecoveryInterceptor().intercept(chain); fail("Expected EOF") }
        catch (actual: EOFException) { assertSame(error, actual) }
        verify(exactly = 2) { chain.proceed(any()) }
    }

    @Test fun writesAndSummariesAreNeverReplayed() {
        for ((method, path) in listOf("POST" to "/diaries/", "PUT" to "/diaries/1",
            "PATCH" to "/agent/tasks/1", "DELETE" to "/diaries/1", "GET" to "/diaries/summaries")) {
            val chain = chain(method, path)
            every { chain.proceed(any()) } throws EOFException()
            try { ReadRecoveryInterceptor().intercept(chain); fail("Expected EOF") }
            catch (_: EOFException) { }
            verify(exactly = 1) { chain.proceed(any()) }
        }
    }

    @Test fun canceledAndTimedOutReadsAreNotRetried() {
        val canceled = chain()
        every { canceled.call().isCanceled() } returns true
        every { canceled.proceed(any()) } throws EOFException()
        try { ReadRecoveryInterceptor().intercept(canceled); fail("Expected EOF") }
        catch (_: EOFException) { }
        verify(exactly = 1) { canceled.proceed(any()) }
        val timedOut = chain()
        every { timedOut.proceed(any()) } throws SocketTimeoutException()
        try { ReadRecoveryInterceptor().intercept(timedOut); fail("Expected timeout") }
        catch (_: SocketTimeoutException) { }
        verify(exactly = 1) { timedOut.proceed(any()) }
    }
}
