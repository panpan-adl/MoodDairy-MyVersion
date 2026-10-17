package com.example.mydiary.ui.chat

import com.example.mydiary.di.AuthInterceptor
import com.example.mydiary.di.NetworkModule
import com.example.mydiary.memory.MemoryHttp
import io.mockk.mockk
import okhttp3.Interceptor
import okhttp3.logging.HttpLoggingInterceptor
import org.junit.Assert.assertFalse
import org.junit.Test

class RequestReplayPolicyTest {
    @Test fun clientsDoNotTransparentlyReplayWritesAfterConnectionLoss() {
        val logging = HttpLoggingInterceptor()
        val close = mockk<Interceptor>()
        val auth = mockk<AuthInterceptor>()
        assertFalse(NetworkModule.provideOkHttpClient(logging, close, auth).retryOnConnectionFailure)
        assertFalse(NetworkModule.provideLongTimeoutOkHttpClient(logging, close, auth).retryOnConnectionFailure)
        assertFalse(MemoryHttp.client().retryOnConnectionFailure)
    }
}
