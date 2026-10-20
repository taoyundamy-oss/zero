package com.zero.v2.services.llm

import org.json.JSONObject

/** A malformed or ambiguous batch must not silently attach vectors to the wrong source. */
internal object EmbeddingResponseDecoder {
    fun usage(body: String, model: String): com.zero.v2.core.common.TokenUsage? = runCatching {
        val usage = requireNotNull(JSONObject(body).optJSONObject("usage"))
        val value = usage.opt("prompt_tokens") ?: usage.opt("input_tokens") ?: usage.opt("total_tokens")
        val number = requireNotNull(value as? Number)
        val tokens = number.toLong()
        require(tokens >= 0 && number.toDouble() == tokens.toDouble())
        com.zero.v2.core.common.TokenUsage(inputTokens = tokens, totalTokens = tokens, model = model)
    }.getOrNull()
    fun decode(body: String, size: Int, native: Boolean): List<FloatArray?> {
        val missing = List<FloatArray?>(size) { null }
        val json = JSONObject(body)
        val rows = if (native) json.optJSONObject("output")?.optJSONArray("embeddings") else json.optJSONArray("data")
        if (rows == null || rows.length() != size) return missing
        val slots = arrayOfNulls<FloatArray>(size)
        return runCatching {
            for (i in 0 until rows.length()) {
                val row = rows.getJSONObject(i)
                val index = requireNotNull(row.opt(if (native) "text_index" else "index") as? Number)
                val slot = index.toInt()
                require(index.toDouble() == slot.toDouble() && slot in slots.indices && slots[slot] == null)
                slots[slot] = vector(row)
            }
            slots.toList()
        }.getOrDefault(missing)
    }

    private fun vector(row: JSONObject): FloatArray {
        val values = row.getJSONArray("embedding")
        val result = FloatArray(values.length()) { j ->
            (values.opt(j) as? Number)?.toFloat() ?: Float.NaN
        }
        require(result.all { it.isFinite() } && result.any { it != 0f })
        return result
    }
}
