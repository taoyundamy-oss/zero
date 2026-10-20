package com.zero.v2.services.llm

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderUsageTest {
    @Test fun `missing total cannot bypass budget accounting of known input and output`() {
        val usage = ProviderUsage.read(JSONObject().put("prompt_tokens", 100)
            .put("prompt_cache_hit_tokens", 90).put("completion_tokens", 5))
        assertEquals(105L, usage.totalTokens)
        val partial = ProviderUsage.read(JSONObject().put("completion_tokens", 5))
        assertEquals(5L, partial.totalTokens)
        assertTrue(!partial.inputObserved)
    }

    @Test fun `absent null invalid and explicit zero cache are distinct`() {
        fun read(cache: Any?) = ProviderUsage.read(JSONObject().put("prompt_tokens", 100)
            .apply { if (cache != null) put("prompt_cache_hit_tokens", cache) })
        for (value in listOf(null, JSONObject.NULL, -1, 101, "invalid")) {
            assertEquals(0L, read(value).cacheObservedInputTokens)
            assertTrue(read(value).cacheSummary.contains("unknown"))
        }
        assertEquals(100L, read(0).cacheObservedInputTokens)
        assertTrue(read(0).cacheSummary.contains("0%"))
    }

    @Test fun `missing individual usage fields are not known zero`() {
        val missing = ProviderUsage.read(JSONObject())
        assertTrue(!missing.inputObserved && !missing.outputObserved)
        val zero = ProviderUsage.read(JSONObject().put("prompt_tokens", 0).put("completion_tokens", 0))
        assertTrue(zero.inputObserved && zero.outputObserved)
    }

    @Test fun `mixed observations preserve weighted denominator coverage and lower bound`() {
        val known = ProviderUsage.read(JSONObject().put("prompt_tokens", 100).put("prompt_cache_hit_tokens", 90))
        val unknown = ProviderUsage.read(JSONObject().put("prompt_tokens", 100))
        val sum = known + unknown
        assertEquals(200L, sum.inputTokens)
        assertEquals(100L, sum.cacheObservedInputTokens)
        assertEquals(90L, sum.cachedTokens)
        assertTrue(sum.cacheSummary.contains("90%"))
        assertTrue(sum.cacheSummary.contains("覆盖率 50%"))
        assertTrue(sum.cacheSummary.contains("下界 45%"))
    }

    @Test fun `stream and failed responses retain unknown cache instead of observed zero`() {
        val usage = JSONObject().put("input_tokens", 100).put("output_tokens", 10)
        assertEquals(0L, ResponsesFailure(JSONObject().put("usage", usage)).usage!!.cacheObservedInputTokens)
        usage.put("input_tokens_details", JSONObject().put("cached_tokens", 0))
        assertEquals(100L, ResponsesFailure(JSONObject().put("usage", usage)).usage!!.cacheObservedInputTokens)
        val payload = """{"usage":{"prompt_tokens":100,"prompt_tokens_details":{"cached_tokens":80}}}"""
        val chat = OpenAiStreamDecoder.decode(payload, "m")
        assertEquals(100L, chat.single().tokenUsage!!.cacheObservedInputTokens)
        assertEquals(80L, chat.single().tokenUsage!!.cachedTokens)
    }
    @Test fun `missing entire usage remains visible beside otherwise complete telemetry`() {
        val known = ProviderUsage.read(JSONObject().put("prompt_tokens", 100).put("prompt_cache_hit_tokens", 90))
        val sum = known + ProviderUsage.read(JSONObject())
        assertEquals(1L, sum.cacheUnknownSamples)
        assertTrue(sum.cacheSummary.contains("未知记录 1"))
        assertTrue(sum.cacheSummary.contains("下界"))
    }

}
