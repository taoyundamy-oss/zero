package com.zero.v2.services.termux

import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SupervisorAuthorizationTest {
    @Test fun `credential stays on exact paired loopback endpoint`() {
        val token = "a".repeat(64)
        val request = Request.Builder().url("http://127.0.0.1:7878/exec").build()
        assertEquals("Bearer $token", SupervisorAuthorization.authorize(request, 7878, token).header("Authorization"))
        for (url in listOf("https://example.com/exec", "http://127.0.0.1:7879/exec")) {
            assertTrue(runCatching {
                SupervisorAuthorization.authorize(request.newBuilder().url(url).build(), 7878, token)
            }.isFailure)
        }
        assertTrue(runCatching { SupervisorAuthorization.authorize(request, 7878, null) }.isFailure)
    }
}
