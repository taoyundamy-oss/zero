package com.zero.v2.services.mcp

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class ModuleStartReceiptTest {
    @Test fun emptyReplyAdoptsOnlyMatchingReceiptWithoutAnotherStart() = runBlocking {
        Wire().use { wire ->
            val result = confirmedModuleStart(OkHttpClient(), wire.url, JSONObject())
            assertTrue(result!!.getBoolean("ok"))
            assertEquals(1, wire.starts.get())
            assertEquals(1, wire.lookups.get())
        }
    }
    @Test fun oldReceiptCannotBeAdoptedOrCauseBlindRestart() = runBlocking {
        Wire(old = true).use { wire ->
            val result = confirmedModuleStart(OkHttpClient(), wire.url, JSONObject())
            assertFalse(result!!.getBoolean("ok"))
            assertTrue(result.getBoolean("uncertain"))
            assertEquals("start_response_unconfirmed", result.getString("error"))
            assertEquals(1, wire.starts.get())
        }
    }
    @Test fun malformedAndHttpFailureRemainClassified() = runBlocking {
        for ((kind, reason) in listOf("bad" to "start_reply_invalid", "http" to "start_http_503",
            "incomplete" to "start_reply_invalid", "not_boolean" to "start_reply_invalid")) {
            Wire(kind = kind).use { wire ->
                val result = postModuleStart(OkHttpClient(), wire.url, JSONObject())
                assertTrue(result!!.getBoolean("uncertain"))
                assertEquals(reason, result.getString("error"))
            }
        }
    }
    @Test fun cancellationDuringReceiptCannotPublishOrRepeatStart() = runBlocking {
        Wire(wait = true).use { wire ->
            var published = false
            val job = launch(Dispatchers.IO) {
                confirmedModuleStart(OkHttpClient(), wire.url, JSONObject())
                published = true
            }
            assertTrue(wire.entered.await(5, TimeUnit.SECONDS))
            job.cancelAndJoin()
            wire.release.countDown()
            assertFalse(published)
            assertEquals(1, wire.starts.get())
            assertEquals(1, wire.lookups.get())
        }
    }
}

private class Wire(
    private val old: Boolean = false,
    private val kind: String = "empty",
    private val wait: Boolean = false,
) : AutoCloseable {
    private val listener = ServerSocket(0)
    val url get() = "http://127.0.0.1:${listener.localPort}/module/owned/start"
    val starts = AtomicInteger()
    val lookups = AtomicInteger()
    val entered = CountDownLatch(1)
    val release = CountDownLatch(1)
    private val pool = Executors.newCachedThreadPool()
    @Volatile private var requestId = ""
    init {
        pool.submit {
            while (!listener.isClosed) {
                val socket = runCatching { listener.accept() }.getOrNull() ?: break
                pool.submit { runCatching { socket.use { serve(it) } } }
            }
        }
    }
    private fun serve(socket: Socket) {
        val input = socket.getInputStream().buffered()
        fun line() = readLine(input)
        val request = line()
        var length = 0
        while (true) {
            val h = line()
            if (h.isEmpty()) break
            if (h.startsWith("Content-Length:", true)) length = h.substringAfter(':').trim().toInt()
        }
        val body = ByteArray(length)
        var offset = 0
        while (offset < length) {
            val count = input.read(body, offset, length - offset)
            if (count < 0) return
            offset += count
        }
        val payload = payload(request, body).toByteArray()
        val status = if (kind == "http") 503 else 200
        val output = socket.getOutputStream()
        output.write(
            "HTTP/1.1 $status Result\r\nContent-Length: ${payload.size}\r\nConnection: close\r\n\r\n".toByteArray(),
        )
        output.write(payload); output.flush()
    }
    private fun payload(request: String, body: ByteArray): String = if (request.startsWith("POST")) {
            starts.incrementAndGet()
            requestId = JSONObject(body.toString(Charsets.UTF_8)).optString("request_id")
            when (kind) {
                "bad" -> "invalid JSON"
                "incomplete" -> "{\"ok\":true}"
                "not_boolean" -> "{\"ok\":\"yes\"}"
                else -> ""
            }
        } else {
            lookups.incrementAndGet(); entered.countDown()
            if (wait) release.await(5, TimeUnit.SECONDS)
            JSONObject().put("ok", true).put("reconciled", true)
                .put("request_id", if (old) "obsolete" else requestId)
                .put("endpoint", "http://127.0.0.1:4321").toString()
        }
    private fun readLine(input: java.io.InputStream): String = buildString {
        while (true) {
            val c = input.read()
            if (c < 0 || c == 10) break
            if (c != 13) append(c.toChar())
        }
    }
    override fun close() { release.countDown(); listener.close(); pool.shutdownNow() }
}
