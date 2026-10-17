package com.example.mydiary.data.network

import okhttp3.Interceptor
import okhttp3.Response
import okhttp3.ResponseBody
import okio.Buffer
import okio.ForwardingSource
import okio.buffer
import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit

internal class RequestDiagnosticsInterceptor(private val report: (String) -> Unit) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val id = UUID.randomUUID().toString()
        val request = chain.request().newBuilder().header("X-Request-ID", id).build()
        val start = System.nanoTime()
        val label = "request_id=$id method=${request.method} path=${request.url.encodedPath}"
        report("request.start $label")
        return try {
            val response = chain.proceed(request)
            report("request.response_headers $label status=${response.code} elapsed_ms=${TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)}")
            val body = response.body ?: return response
            val source = object : ForwardingSource(body.source()) {
                private var completed = false
                override fun read(sink: Buffer, byteCount: Long): Long = try {
                    val count = super.read(sink, byteCount)
                    if (count == -1L && !completed) {
                        completed = true
                        report("request.response_received $label")
                    }
                    count
                } catch (error: IOException) {
                    report("request.body_failed $label error_type=${error.javaClass.simpleName}")
                    throw error
                }
            }.buffer()
            response.newBuilder().body(object : ResponseBody() {
                override fun contentType() = body.contentType()
                override fun contentLength() = body.contentLength()
                override fun source() = source
            }).build()
        } catch (error: IOException) {
            report("request.failed $label error_type=${error.javaClass.simpleName} elapsed_ms=${TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)}")
            throw error
        }
    }
}
