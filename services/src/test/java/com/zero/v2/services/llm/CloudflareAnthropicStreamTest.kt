package com.zero.v2.services.llm

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudflareAnthropicStreamTest {
    @Test fun `input observation survives interruption before message stop`() {
        val decoder = CloudflareAnthropicStream("m")
        val chunks = decoder.decode(JSONObject("""{"type":"message_start","message":{"usage":
            {"input_tokens":2,"cache_read_input_tokens":10,"cache_creation_input_tokens":3}}}"""))
        val usage = chunks.singleOrNull()?.tokenUsage
        assertEquals(15L, usage?.inputTokens)
        assertEquals(10L, usage?.cachedTokens)
        assertEquals(false, usage?.outputObserved)
        assertTrue(chunks.none { it.isFinal })
    }

    @Test fun `partial usage updates retain creation tokens and replace cumulative counts`() {
        val decoder = CloudflareAnthropicStream("m")
        decoder.decode(JSONObject("""{"type":"message_start","message":{"usage":
            {"input_tokens":2,"cache_read_input_tokens":10,"cache_creation_input_tokens":3,"output_tokens":1}}}"""))
        decoder.decode(JSONObject("""{"type":"message_delta","delta":{"stop_reason":"end_turn"},
            "usage":{"input_tokens":4,"output_tokens":5}}"""))
        val usage = decoder.decode(JSONObject("""{"type":"message_stop"}""")).single().tokenUsage!!
        assertEquals(17L, usage.inputTokens)
        assertEquals(5L, usage.outputTokens)
        assertEquals(22L, usage.totalTokens)
    }

    private fun started() = CloudflareAnthropicStream("anthropic/claude-haiku-4.5").also {
        it.decode(JSONObject("""{"type":"message_start","message":{"usage":{"input_tokens":22,"output_tokens":2}}}"""))
    }

    @Test fun `captured CF Haiku text and final usage require message stop`() {
        val decoder = started()
        val text = decoder.decode(JSONObject("""{"type":"content_block_delta","index":0,
            "delta":{"type":"text_delta","text":"42"}}""")).single()
        assertEquals("42", text.contentDelta)
        assertEquals(5L, decoder.decode(JSONObject("""{"type":"message_delta","delta":{"stop_reason":"end_turn"},
            "usage":{"input_tokens":22,"output_tokens":5}}""")).single().tokenUsage!!.outputTokens)
        val finish = decoder.decode(JSONObject("""{"type":"message_stop"}""")).single()
        assertEquals("stop", finish.finishReason)
        assertEquals(27L, finish.tokenUsage!!.totalTokens)
    }

    @Test fun `missing native usage remains unknown and fractional cache is rejected`() {
        for (raw in listOf("{}", "{\"input_tokens\":2,\"cache_read_input_tokens\":1.5}")) {
            val decoder = CloudflareAnthropicStream("m")
            decoder.decode(JSONObject().put("type", "message_start")
                .put("message", JSONObject().put("usage", JSONObject(raw))))
            decoder.decode(JSONObject("""{"type":"message_delta","delta":{"stop_reason":"end_turn"}}"""))
            val usage = decoder.decode(JSONObject("""{"type":"message_stop"}""")).single().tokenUsage!!
            assertTrue(!usage.inputObserved)
            assertTrue(!usage.outputObserved)
            assertEquals(1L, usage.cacheUnknownSamples)
        }
    }

    @Test fun `tool id name and partial JSON preserve block index`() {
        val decoder = started()
        val start = decoder.decode(JSONObject("""{"type":"content_block_start","index":2,
            "content_block":{"type":"tool_use","id":"call1","name":"probe","input":{}}}""")).single().toolCallDelta!!
        assertEquals(2, start.index)
        assertEquals("call1", start.id)
        assertEquals("probe", start.name)
        assertEquals(null, start.argumentsDelta)
        val delta = decoder.decode(JSONObject("""{"type":"content_block_delta","index":2,
            "delta":{"type":"input_json_delta","partial_json":"{\"step\":1}"}}""")).single().toolCallDelta!!
        assertEquals(2, delta.index)
        assertEquals("{\"step\":1}", delta.argumentsDelta)
        decoder.decode(JSONObject("""{"type":"message_delta","delta":{"stop_reason":"tool_use"}}"""))
        assertEquals("tool_calls", decoder.decode(JSONObject("""{"type":"message_stop"}""")).single().finishReason)
    }

    @Test fun `native thinking unknown events and incomplete state fail closed`() {
        for (event in listOf(
            """{"type":"content_block_start","content_block":{"type":"thinking"}}""",
            """{"type":"content_block_start","content_block":{"type":"redacted_thinking","data":"opaque"}}""",
            """{"type":"content_block_delta","delta":{"type":"thinking_delta","thinking":"private"}}""",
            """{"type":"content_block_delta","delta":{"type":"signature_delta"}}""",
            """{"type":"message_stop"}""", """{"type":"unknown"}""",
            """{"type":"error","error":{"message":"unavailable"}}""",
        )) assertTrue(runCatching { started().decode(JSONObject(event)) }.isFailure)
        assertTrue(runCatching { CloudflareAnthropicStream("m").decode(JSONObject(
            """{"type":"content_block_delta","delta":{"type":"text_delta","text":"42"}}""")) }.isFailure)
    }

    @Test fun `cached and created tokens count toward total input`() {
        val decoder = started()
        decoder.decode(JSONObject("""{"type":"message_delta","delta":{"stop_reason":"max_tokens"},
            "usage":{"input_tokens":2,"cache_read_input_tokens":10,
            "cache_creation_input_tokens":3,"output_tokens":5}}"""))
        val final = decoder.decode(JSONObject("""{"type":"message_stop"}""")).single()
        assertEquals("length", final.finishReason)
        assertEquals(15L, final.tokenUsage!!.inputTokens)
        assertEquals(10L, final.tokenUsage!!.cachedTokens)
    }
}
