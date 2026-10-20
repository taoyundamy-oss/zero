package com.zero.v2.services.chatgpt

import java.io.Closeable
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Only one validated callback consumes an attempt; pending secrets never survive process death. */
internal class ChatGptCallback : Closeable {
    private val listener = ServerSocket(0, BACKLOG, InetAddress.getByName("127.0.0.1")).apply { soTimeout = POLL_MS }
    val redirect = "http://127.0.0.1:${listener.localPort}/auth/callback"
    suspend fun await(attempt: ChatGptOAuth.Attempt): ChatGptOAuth.Callback {
        val deadline = System.nanoTime() + TIMEOUT_NANOS
        while (System.nanoTime() < deadline) {
            currentCoroutineContext().ensureActive()
            val socket = try { listener.accept() } catch (_: SocketTimeoutException) { continue }
            val result = socket.use {
                it.soTimeout = POLL_MS
                val callback = readCallback(it, attempt, deadline)
                respond(it, callback != null)
                callback
            }
            if (result != null) return result
        }
        error("ChatGPT sign-in timed out")
    }
    private suspend fun readCallback(socket: Socket, attempt: ChatGptOAuth.Attempt,
        deadline: Long): ChatGptOAuth.Callback? = try {
        val header = StringBuilder()
        while (header.length < MAX_HEADER && !header.endsWith("\r\n\r\n")) {
            currentCoroutineContext().ensureActive()
            require(System.nanoTime() < deadline)
            val byte = socket.inputStream.read()
            if (byte < 0) break
            header.append(byte.toChar())
        }
        require(header.endsWith("\r\n\r\n"))
        val lines = header.toString().split("\r\n")
        val request = lines.first().split(' ')
        require(request.size == REQUEST_PARTS && request[0] == "GET")
        require(lines.count { h -> h.equals("Host: 127.0.0.1:${listener.localPort}", true) } == 1)
        require(request[1].startsWith("/auth/callback?"))
        ChatGptOAuth.callback("http://127.0.0.1:${listener.localPort}${request[1]}", attempt)
    } catch (_: IllegalArgumentException) {
        null // Ignore forged requests without consuming the real attempt.
    } catch (_: java.io.IOException) {
        null // Partial connections are bounded by the per-read timeout and attempt deadline.
    }
    private fun respond(socket: Socket, accepted: Boolean) {
        val status = if (accepted) "200 OK" else "400 Bad Request"
        val body = "Return to Zero to view sign-in status."
        runCatching {
            socket.outputStream.write(("HTTP/1.1 $status\r\nContent-Length: ${body.length}\r\n" +
                "Content-Type: text/plain\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n$body").toByteArray())
            socket.outputStream.flush()
        }
    }
    override fun close() = listener.close()
    companion object {
        private const val BACKLOG = 4
        private const val REQUEST_PARTS = 3
        private const val POLL_MS = 1000
        private const val MAX_HEADER = 8192
        private const val TIMEOUT_NANOS = 300_000_000_000L
    }
}
