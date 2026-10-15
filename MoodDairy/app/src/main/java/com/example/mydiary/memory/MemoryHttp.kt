package com.example.mydiary.memory

import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/** Keep adb reverse requests independent of expired HTTP keep-alive connections. */
internal object MemoryHttp {
    fun client(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .writeTimeout(90, TimeUnit.SECONDS)
        .callTimeout(90, TimeUnit.SECONDS)
        .addInterceptor { chain ->
            chain.proceed(chain.request().newBuilder().header("Connection", "close").build())
        }
        .retryOnConnectionFailure(true)
        .build()
}
