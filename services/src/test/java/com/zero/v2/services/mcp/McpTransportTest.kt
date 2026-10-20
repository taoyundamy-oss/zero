package com.zero.v2.services.mcp

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.json.JSONObject
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

class McpTransportTest {
    @Test fun `coroutine cancellation promptly closes a stalled HTTP stream`() = runBlocking {
        Fixture("HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nConnection: close\r\n\r\n: waiting\n\n")
            .use { fixture ->
                val started = System.nanoTime()
                var cancelled = false
                try {
                    kotlinx.coroutines.withTimeout(CANCEL_AFTER_MS) { McpTransport(fixture.url).rpc("tools/call") }
                } catch (_: kotlinx.coroutines.TimeoutCancellationException) { cancelled = true }
                assertTrue(cancelled)
                assertTrue(System.nanoTime() - started < RETURN_BUDGET_NS)
            }
    }
    @Test fun `SSE result returns before server closes its stream`() = runBlocking {
        val response = "HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nConnection: close\r\n\r\n" +
            "data: {\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"ok\":true}}\n\n"
        Fixture(response).use { fixture ->
            val started = System.nanoTime()
            val result = McpTransport(fixture.url).rpc("tools/call", JSONObject())
            assertTrue(result.getJSONObject("result").getBoolean("ok"))
            assertTrue("Complete MCP event must not wait for EOF", System.nanoTime() - started < RETURN_BUDGET_NS)
        }
    }

    @Test fun `initialized notification has no request id and accepts empty 202`() = runBlocking {
        Fixture("HTTP/1.1 202 Accepted\r\nContent-Length: 0\r\nConnection: close\r\n\r\n").use { fixture ->
            McpTransport(fixture.url).rpc("notifications/initialized")
            assertFalse(JSONObject(fixture.body.get()).has("id"))
        }
    }

    private class Fixture(response: String) : AutoCloseable {
        private val server = ServerSocket(0)
        private val release = CountDownLatch(1)
        val body = AtomicReference("")
        val url = "http://127.0.0.1:${server.localPort}/mcp"
        private val worker = thread(isDaemon = true) {
            server.accept().use { socket ->
                val reader = socket.getInputStream().bufferedReader()
                var length = 0
                while (true) {
                    val line = reader.readLine().orEmpty()
                    if (line.isEmpty()) break
                    if (line.startsWith("Content-Length:", true)) length = line.substringAfter(':').trim().toInt()
                }
                val payload = CharArray(length)
                var offset = 0
                while (offset < length) {
                    val count = reader.read(payload, offset, length - offset)
                    if (count < 0) break
                    offset += count
                }
                body.set(String(payload))
                socket.getOutputStream().apply { write(response.toByteArray()); flush() }
                release.await(HOLD_OPEN_MS, TimeUnit.MILLISECONDS)
            }
        }
        override fun close() { release.countDown(); server.close(); worker.join(HOLD_OPEN_MS) }
    }

    private companion object {
        const val HOLD_OPEN_MS = 2500L
        const val RETURN_BUDGET_NS = 1_500_000_000L
        const val CANCEL_AFTER_MS = 250L
    }
}
