package com.zero.v2.services.mcp

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class McpResponseReaderTest {
    @Test fun `progress and other response ids cannot replace the requested multiline result`() {
        val events = "data: {\"method\":\"notifications/progress\",\"params\":{}}\n\n" +
            "data: {\"id\":2,\"result\":\"wrong\"}\n\n" +
            "data: {\"id\":1,\n" + "data: \"result\":\"right\"}\n\n"
        events.toResponseBody("text/event-stream".toMediaType()).use {
            assertEquals("right", McpResponseReader.read(it, 1).getString("result"))
        }
    }

    @Test fun `closed stream without a response is an explicit failure`() {
        "data: {\"method\":\"notifications/progress\"}\n\n"
            .toResponseBody("text/event-stream".toMediaType()).use { body ->
                assertThrows(IllegalStateException::class.java) { McpResponseReader.read(body, 1) }
            }
    }

    @Test fun `batched json selects the matching result`() {
        "[{\"id\":2,\"result\":\"wrong\"},{\"id\":1,\"result\":\"right\"}]"
            .toResponseBody("application/json".toMediaType()).use {
                assertEquals("right", McpResponseReader.read(it, 1).getString("result"))
            }
    }
}
