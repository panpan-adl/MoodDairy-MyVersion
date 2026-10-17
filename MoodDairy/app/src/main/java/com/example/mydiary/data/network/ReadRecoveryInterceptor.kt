package com.example.mydiary.data.network

import java.io.EOFException
import java.io.IOException
import java.net.SocketException
import okhttp3.Interceptor
import okhttp3.Response

/** Recover one interrupted read, never replay a write or a summary with its own retry. */
internal class ReadRecoveryInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val canRetry = request.method in setOf("GET", "HEAD") &&
            request.url.encodedPath.trimEnd('/') != "/diaries/summaries"
        return try {
            chain.proceed(request)
        } catch (error: IOException) {
            val interrupted = error is EOFException || error is SocketException ||
                error.message?.contains("unexpected end of stream", ignoreCase = true) == true
            if (!canRetry || !interrupted || chain.call().isCanceled()) throw error
            chain.proceed(request)
        }
    }
}
