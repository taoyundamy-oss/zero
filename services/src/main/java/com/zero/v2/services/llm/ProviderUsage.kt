package com.zero.v2.services.llm

import com.zero.v2.core.common.TokenUsage
import org.json.JSONObject

/** Missing, null or invalid cache counts are unknown, never observed misses. */
internal object ProviderUsage {
    fun read(value: JSONObject, model: String = "", responses: Boolean = false): TokenUsage {
        val inputKey = if (responses) "input_tokens" else "prompt_tokens"
        val outputKey = if (responses) "output_tokens" else "completion_tokens"
        val input = count(value, inputKey) ?: 0
        val output = count(value, outputKey) ?: 0
        val cached = cached(value, responses)
        val observed = count(value, inputKey) != null && cached != null && cached <= input
        return TokenUsage(inputTokens = input, cachedTokens = if (observed) requireNotNull(cached) else 0,
            outputTokens = output, totalTokens = count(value, "total_tokens")
                ?: (input + output.coerceAtMost(Long.MAX_VALUE - input)),
            model = model, cacheObservedInputTokens = if (observed) input else 0,
            cacheUnknownSamples = if (observed) 0 else 1,
            inputObserved = count(value, inputKey) != null,
            outputObserved = count(value, outputKey) != null,
            cacheWriteTokens = count(value, "cache_creation_input_tokens"))
    }

    private fun cached(value: JSONObject, responses: Boolean): Long? =
        if (responses) value.optJSONObject("input_tokens_details")?.let { count(it, "cached_tokens") }
        else count(value, "prompt_cache_hit_tokens")
            ?: value.optJSONObject("prompt_tokens_details")?.let { count(it, "cached_tokens") }
            ?: count(value, "cache_read_input_tokens")

    private fun count(value: JSONObject, key: String): Long? = (value.opt(key) as? Number)?.let {
        it.toLong().takeIf { count -> count >= 0 && count.toDouble() == it.toDouble() }
    }
}
