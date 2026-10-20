package com.zero.v2.services.chatgpt

import java.net.Socket
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatGptCallbackTest {
    @Test fun wrongStateDoesNotConsumeRealCallbackAndCancelClosesPort() = runBlocking {
        val listener = ChatGptCallback()
        val attempt = ChatGptOAuth.Attempt("host", listener.redirect)
        val port = java.net.URI(listener.redirect).port
        val waiting = async(Dispatchers.IO) { listener.use { it.await(attempt) } }
        fun request(state: String): String = Socket("127.0.0.1", port).use {
            it.soTimeout = 3000
            it.getOutputStream().write(("GET /auth/callback?state=$state&code=synthetic" +
                "&client_id=oaiapp_test HTTP/1.1\r\n" +
                "Host: 127.0.0.1:$port\r\n\r\n").toByteArray())
            it.getInputStream().bufferedReader().readLine()
        }
        assertTrue(request("wrong").contains("400"))
        assertTrue(request(attempt.state).contains("200"))
        assertEquals("synthetic", waiting.await().code)
        assertTrue(runCatching { Socket("127.0.0.1", port).close() }.isFailure)
        val pending = ChatGptCallback()
        val pendingPort = java.net.URI(pending.redirect).port
        val started = kotlinx.coroutines.CompletableDeferred<Unit>()
        val cancelled = async(Dispatchers.IO) { pending.use {
            started.complete(Unit)
            it.await(ChatGptOAuth.Attempt("host", it.redirect))
        } }
        started.await()
        cancelled.cancelAndJoin()
        assertTrue(runCatching { Socket("127.0.0.1", pendingPort).close() }.isFailure)
    }
}
