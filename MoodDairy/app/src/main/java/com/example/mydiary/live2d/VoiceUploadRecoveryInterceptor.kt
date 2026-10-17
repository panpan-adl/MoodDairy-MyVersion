package com.example.mydiary.live2d

import java.io.EOFException
import java.io.IOException
import java.io.InterruptedIOException
import java.net.SocketException
import okhttp3.Interceptor
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody

/** Only short ASR is replayable: this endpoint never sends chat messages or writes diaries. */
internal class VoiceUploadRecoveryInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (request.method != "POST" || request.url.encodedPath != "/voice/companion-transcribe") {
            return chain.proceed(request)
        }
        fun attempt(): Response {
            val response = chain.proceed(request)
            if (!response.isSuccessful) return response
            // Include response-body disconnects in recovery; close before replaying.
            return try {
                val body = response.body ?: return response
                response.newBuilder().body(body.bytes().toResponseBody(body.contentType())).build()
            } catch (error: IOException) {
                response.close()
                throw error
            }
        }
        return try { attempt() } catch (error: IOException) {
            val disconnected = error is EOFException || error is SocketException ||
                error.message?.contains("unexpected end of stream", ignoreCase = true) == true
            if (!disconnected || error is InterruptedIOException || chain.call().isCanceled()) throw error
            // One replay of the same in-memory audio, retaining authentication.
            attempt()
        }
    }
}

internal object VoiceRecognitionErrors {
    fun connection(error: IOException): String = if (error is InterruptedIOException)
        "语音识别超时，请稍后再试"
    else "语音连接已中断，请检查网络后再说一次"

    fun response(code: Int): String = when (code) {
        401, 403 -> "登录已过期，请重新登录后使用语音"
        408, 504 -> "语音识别超时，请稍后再试"
        503 -> "语音识别服务暂不可用，请稍后再试"
        else -> "语音识别暂不可用，请再试一次"
    }
}
