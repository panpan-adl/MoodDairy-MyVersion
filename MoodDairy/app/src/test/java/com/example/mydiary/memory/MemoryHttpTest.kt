package com.example.mydiary.memory

import okhttp3.Request
import org.junit.Assert.*
import org.junit.Test
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class MemoryHttpTest {
    @Test fun consecutiveRequestsReleaseConnectionsInsteadOfReusingTheTunnel() {
        ServerSocket(0, 2, java.net.InetAddress.getByName("127.0.0.1")).use { server ->
            server.soTimeout = 5000
            val executor = Executors.newSingleThreadExecutor()
            val client = MemoryHttp.client()
            try {
                val received = executor.submit<List<List<String>>> {
                    (1..2).map {
                        server.accept().use { socket ->
                            socket.soTimeout = 5000
                            val reader = socket.getInputStream().bufferedReader()
                            val headers = mutableListOf<String>()
                            while (true) {
                                val line = reader.readLine() ?: error("Request ended before headers")
                                if (line.isEmpty()) break
                                headers.add(line)
                            }
                            // Deliberately offer keep-alive, as the backend does. The client must
                            // still release this socket before the next request uses the tunnel.
                            socket.getOutputStream().apply {
                                write("HTTP/1.1 200 OK\r\nContent-Length: 2\r\nConnection: keep-alive\r\n\r\n[]".toByteArray())
                                flush()
                            }
                            assertEquals(-1, socket.getInputStream().read())
                            headers.toList()
                        }
                    }
                }
                repeat(2) {
                    val request = Request.Builder().url("http://127.0.0.1:${server.localPort}/future-letters").build()
                    client.newCall(request).execute().use { assertEquals("[]", it.body!!.string()) }
                }
                received.get(10, TimeUnit.SECONDS).forEach { headers ->
                    assertTrue(headers.any { it.equals("Connection: close", ignoreCase = true) })
                }
            } finally {
                client.connectionPool.evictAll()
                client.dispatcher.executorService.shutdownNow()
                executor.shutdownNow()
            }
        }
    }

    @Test fun incompleteResponseIsNeverAcceptedAsSuccessfulContent() {
        ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1")).use { server ->
            server.soTimeout = 5000
            val executor = Executors.newSingleThreadExecutor()
            val client = MemoryHttp.client()
            try {
                val sender = executor.submit {
                    server.accept().use { socket ->
                        socket.soTimeout = 5000
                        val reader = socket.getInputStream().bufferedReader()
                        while (!reader.readLine().isNullOrEmpty()) { }
                        socket.getOutputStream().apply {
                            write("HTTP/1.1 200 OK\r\nContent-Length: 100\r\n\r\n[".toByteArray())
                            flush()
                        }
                    }
                }
                try {
                    client.newCall(Request.Builder().url("http://127.0.0.1:${server.localPort}/future-letters").build())
                        .execute().use { it.body!!.string() }
                    fail("A truncated response must fail instead of replacing the current data")
                } catch (expected: java.io.IOException) {
                    assertFalse(expected is SocketTimeoutException)
                }
                sender.get(10, TimeUnit.SECONDS)
            } finally {
                client.connectionPool.evictAll()
                client.dispatcher.executorService.shutdownNow()
                executor.shutdownNow()
            }
        }
    }
}
