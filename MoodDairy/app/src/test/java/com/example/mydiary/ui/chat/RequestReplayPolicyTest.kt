package com.example.mydiary.ui.chat

import com.example.mydiary.di.AuthInterceptor
import com.example.mydiary.data.network.ApiEndpoints
import com.example.mydiary.di.NetworkModule
import com.example.mydiary.memory.MemoryHttp
import io.mockk.mockk
import io.mockk.every
import io.mockk.mockkObject
import io.mockk.unmockkObject
import okhttp3.Interceptor
import okhttp3.logging.HttpLoggingInterceptor
import org.junit.Assert.assertFalse
import org.junit.Test

class RequestReplayPolicyTest {
    @Test fun clientsDoNotTransparentlyReplayWritesAfterConnectionLoss() {
        val logging = HttpLoggingInterceptor()
        val close = mockk<Interceptor>()
        val auth = mockk<AuthInterceptor>()
        // Host JVM tests have no Android Build fields; test the replay policy
        // with an explicit endpoint instead of invoking device detection.
        mockkObject(ApiEndpoints)
        try {
            every { ApiEndpoints.apiBaseUrl } returns "http://127.0.0.1:8000/"
            assertFalse(NetworkModule.provideOkHttpClient(logging, close, auth).retryOnConnectionFailure)
            assertFalse(NetworkModule.provideLongTimeoutOkHttpClient(logging, close, auth).retryOnConnectionFailure)
            assertFalse(MemoryHttp.client().retryOnConnectionFailure)
        } finally {
            unmockkObject(ApiEndpoints)
        }
    }
}
