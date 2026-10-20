package com.zero.v2.services.llm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenAiStreamDecoderTest {
    @Test fun `usage-only SSE event is retained`() {
        val data = """{"choices":[],"usage":
            {"prompt_tokens":1000,"prompt_cache_hit_tokens":960,"completion_tokens":8}}"""
        val usage = OpenAiStreamDecoder.decode(data, "model").single().tokenUsage!!
        assertEquals(1000L, usage.inputTokens)
        assertEquals(960L, usage.cachedTokens)
    }

    @Test fun `tool finish without usage is retained`() {
        val finish = OpenAiStreamDecoder.decode("""{"choices":[{"delta":{},"finish_reason":"tool_calls"}]}""", "m")
        assertEquals("tool_calls", finish.single().finishReason)
    }

    @Test fun `provider error and malformed SSE cannot silently disappear`() {
        assertTrue(runCatching { OpenAiStreamDecoder.decode("""{"error":{"message":"failed"}}""", "m") }.isFailure)
        assertTrue(runCatching { OpenAiStreamDecoder.decode("{broken", "m") }.isFailure)
    }
}
