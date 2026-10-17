package com.example.mydiary.data.network

import okhttp3.ConnectionPool
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

internal object LocalApiTransport {
    fun isLocal(host: String) = host in setOf("127.0.0.1", "localhost", "::1", "10.0.2.2")

    fun configure(builder: OkHttpClient.Builder, baseUrl: String): OkHttpClient.Builder {
        if (isLocal(baseUrl.toHttpUrl().host)) {
            // Connection: close alone cannot prevent races with a stale idle socket.
            builder.connectionPool(ConnectionPool(0, 1, TimeUnit.NANOSECONDS))
        }
        return builder.retryOnConnectionFailure(false)
    }
}
