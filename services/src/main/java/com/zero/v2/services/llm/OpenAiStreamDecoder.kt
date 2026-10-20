package com.zero.v2.services.llm

import com.zero.v2.core.common.StreamChunk
import com.zero.v2.core.common.ToolCallDelta
import org.json.JSONObject

/** SSE payload decoding independent of transport and UI speed. Never discard protocol errors. */
internal object OpenAiStreamDecoder {
    fun decode(data: String, modelId: String, providerScope: String? = null): List<StreamChunk> {
        val json = JSONObject(data)
        json.optJSONObject("error")?.let { error ->
            throw java.io.IOException("provider_stream_error: ${error.optString("message").take(ERROR_LIMIT)}")
        }
        val chunks = mutableListOf<StreamChunk>()
        val choice = json.optJSONArray("choices")?.optJSONObject(0)
        val delta = choice?.optJSONObject("delta")
        delta?.text("content")?.let { chunks += StreamChunk(contentDelta = it) }
        delta?.text("reasoning_content")?.let { chunks += StreamChunk(thinkingDelta = it) }
        delta?.optJSONArray("tool_calls")?.let { calls ->
            for (index in 0 until calls.length()) {
                val call = calls.getJSONObject(index)
                val function = call.optJSONObject("function")
                chunks += StreamChunk(toolCallDelta = ToolCallDelta(
                    index = call.optInt("index", index), id = call.text("id"),
                    name = function?.text("name"), argumentsDelta = function?.text("arguments"),
                    providerState = ProviderToolState.read(call, providerScope),
                ))
            }
        }
        val usage = json.optJSONObject("usage")?.let { value ->
            ProviderUsage.read(value, modelId)
        }
        val finish = choice?.text("finish_reason")
        if (usage != null || finish != null) chunks += StreamChunk(
            tokenUsage = usage, finishReason = finish, isFinal = finish != null,
        )
        return chunks
    }

    private fun JSONObject.text(key: String): String? =
        if (isNull(key)) null else optString(key).takeIf { it.isNotEmpty() }
    private const val ERROR_LIMIT = 300
}
