package com.zero.v2.services.llm

import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BoundedCompletionTransportTest {
    private class Server(private val response: String?) : AutoCloseable {
        private val socket = ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress())
        val entered = CountDownLatch(1)
        val calls = AtomicInteger()
        val url = "http://localhost:${socket.localPort}/completion"
        private val release = CountDownLatch(1)
        private val worker = thread(isDaemon = true) {
            socket.accept().use { connection ->
                calls.incrementAndGet()
                entered.countDown()
                val input = connection.getInputStream().bufferedReader()
                while (!input.readLine().isNullOrEmpty()) { /* request headers only */ }
                if (response == null) release.await(10, TimeUnit.SECONDS) else {
                    connection.getOutputStream().write(response.toByteArray())
                    connection.getOutputStream().flush()
                }
            }
        }
        override fun close() {
            release.countDown()
            socket.close()
            worker.join(1000)
        }
    }

    @Test fun stalledServerHasHardDeadlineWithoutRetry() = runBlocking {
        Server(null).use { server ->
            val started = System.nanoTime()
            var outcome = "missing"
            assertNull(BoundedCompletionTransport.execute(OkHttpClient(),
                    Request.Builder().url(server.url).build()) { outcome = it })
            assertEquals("timeout", outcome)
            val elapsedMs = (System.nanoTime() - started) / 1_000_000
            assertTrue("elapsed=$elapsedMs", elapsedMs in 1500..5000)
            assertEquals(1, server.calls.get())
        }
    }

    @Test fun cancellationStopsBlockingTransport() = runBlocking {
        Server(null).use { server ->
            val call = async(Dispatchers.IO) {
                BoundedCompletionTransport.execute(OkHttpClient(), Request.Builder().url(server.url).build())
            }
            assertTrue(server.entered.await(2, TimeUnit.SECONDS))
            val started = System.nanoTime()
            call.cancelAndJoin()
            assertTrue(call.isCancelled)
            assertTrue((System.nanoTime() - started) / 1_000_000 < 1500)
            assertEquals(1, server.calls.get())
        }
    }

    @Test fun oversizedResponseAndRedirectAreRejected() = runBlocking {
        val body = " ".repeat(17000)
        listOf("HTTP/1.1 200 OK\r\nContent-Length: ${body.length}\r\n\r\n$body",
            "HTTP/1.1 302 Found\r\nLocation: http://127.0.0.1:1/forbidden\r\nContent-Length: 0\r\n\r\n")
            .forEach { response ->
                Server(response).use { server ->
                    assertNull(BoundedCompletionTransport.execute(OkHttpClient(),
                        Request.Builder().url(server.url).build()))
                    assertEquals(1, server.calls.get())
                }
            }
    }

    @Test fun failureDiagnosticsContainOnlyCategoryOrHttpStatus() = runBlocking {
        listOf("HTTP/1.1 429 Limited\r\nContent-Length: 6\r\n\r\nsecret" to "http_429",
            "HTTP/1.1 200 OK\r\nContent-Length: 6\r\n\r\nsecret" to "invalid_json").forEach { (wire, expected) ->
            Server(wire).use { server ->
                var outcome = "missing"
                assertNull(BoundedCompletionTransport.execute(OkHttpClient(),
                    Request.Builder().url(server.url).build()) { outcome = it })
                assertEquals(expected, outcome)
                assertEquals(1, server.calls.get())
            }
        }
    }

    @Test fun boundedValidJsonIsDecoded() = runBlocking {
        val body = "{\"ok\":true}"
        Server("HTTP/1.1 200 OK\r\nContent-Length: ${body.length}\r\n\r\n$body").use { server ->
            assertTrue(BoundedCompletionTransport.execute(OkHttpClient(),
                Request.Builder().url(server.url).build())!!.getBoolean("ok"))
        }
    }
}
