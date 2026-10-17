package com.example.mydiary.data.network

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

class LocalApiTransportTest {
    private class Server(port: Int = 0, private val mode: String = "normal") : AutoCloseable {
        private val listener = ServerSocket(port, 50, InetAddress.getByName("127.0.0.1"))
        val port = listener.localPort
        val count = AtomicInteger()
        val ports = Collections.synchronizedList(mutableListOf<Int>())
        val ids = Collections.synchronizedList(mutableListOf<String>())
        private val worker = thread(isDaemon = true) {
            while (!listener.isClosed) {
                try {
                    listener.accept().use { socket ->
                        socket.soTimeout = 2000
                        val input = socket.getInputStream().buffered()
                        while (line(input).isNotBlank()) {
                            val headers = mutableMapOf<String, String>()
                            while (true) {
                                val header = line(input)
                                if (header.isBlank()) break
                                headers[header.substringBefore(':').lowercase()] = header.substringAfter(':').trim()
                            }
                            repeat(headers["content-length"]?.toInt() ?: 0) { input.read() }
                            ports.add(socket.port)
                            ids.add(headers["x-request-id"].orEmpty())
                            count.incrementAndGet()
                            if (mode == "drop") break
                            val response = if (mode == "truncate")
                                "HTTP/1.1 201 Created\r\nContent-Length: 100\r\n\r\nx"
                            else "HTTP/1.1 201 Created\r\nContent-Length: 2\r\nConnection: keep-alive\r\n\r\nok"
                            socket.getOutputStream().write(response.toByteArray())
                            socket.getOutputStream().flush()
                            if (mode == "truncate") break
                        }
                    }
                } catch (_: IOException) { }
            }
        }
        private fun line(input: InputStream): String {
            val result = StringBuilder()
            while (true) {
                val char = input.read()
                if (char == -1 || char == 10) return result.toString().trimEnd('\r')
                result.append(char.toChar())
            }
        }
        override fun close() { listener.close(); worker.join(3000) }
    }

    private fun client(url: String, log: MutableList<String> = mutableListOf()): OkHttpClient =
        LocalApiTransport.configure(OkHttpClient.Builder(), url)
            .addInterceptor(RequestDiagnosticsInterceptor { log.add(it) })
            .addInterceptor(ReadRecoveryInterceptor())
            .build()

    private fun request(url: String) = Request.Builder().url(url)
        .post("private diary body".toRequestBody()).build()

    @Test fun localRequestsUseFreshConnectionsAndResumeAfterServerRestart() {
        var server = Server()
        val port = server.port
        val url = "http://127.0.0.1:$port/diaries/"
        val client = client(url)
        try {
            repeat(2) { client.newCall(request(url)).execute().use { assertEquals("ok", it.body!!.string()) } }
            assertEquals(0, client.connectionPool.idleConnectionCount())
            assertEquals(2, server.ports.toSet().size)
            assertEquals(2, server.ids.toSet().size)
            assertTrue(server.ids.all { it.isNotBlank() })
            server.close()
            server = Server(port)
            client.newCall(request(url)).execute().use { assertEquals("ok", it.body!!.string()) }
        } finally { server.close(); client.connectionPool.evictAll() }
    }

    @Test fun saveIsNotReplayedWhenResponseIsLostOrTruncated() {
        for (mode in listOf("drop", "truncate")) {
            val log = mutableListOf<String>()
            val server = Server(mode = mode)
            val url = "http://127.0.0.1:${server.port}/diaries/"
            val client = client(url, log)
            try {
                try {
                    client.newCall(request(url)).execute().use { it.body!!.string() }
                    fail("A dropped/truncated response must fail")
                } catch (_: IOException) { }
                assertEquals(1, server.count.get())
                assertFalse(client.retryOnConnectionFailure)
                assertTrue(log.any { it.contains("request.start") })
                assertTrue(log.any { it.contains(if (mode == "truncate") "request.body_failed" else "request.failed") })
                assertTrue(log.none { it.contains("private diary body") })
            } finally { server.close(); client.connectionPool.evictAll() }
        }
    }

    @Test fun remoteServerRetainsNormalConnectionPool() {
        val builder = OkHttpClient.Builder()
        val pool = builder.build().connectionPool
        val client = LocalApiTransport.configure(builder, "https://example.com/").build()
        assertSame(pool, client.connectionPool)
    }
}
