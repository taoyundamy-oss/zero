package com.zero.v2.services.llm

import com.zero.v2.core.common.StreamChunk
import com.zero.v2.core.common.TokenUsage
import com.zero.v2.core.common.ToolCallDelta
import java.io.IOException
import org.json.JSONObject

/** CF REST may return native Anthropic SSE for an OpenAI-shaped request. One instance per response. */
internal class CloudflareAnthropicStream(private val model: String, scope: String? = null) {
    private val content = scope?.let(::AnthropicContentAssembly)
    private var input = 0L
    private var plainInput: Long? = null
    private var createdInput: Long? = 0L
    private var output = 0L
    private var cached = 0L
    private var cacheKnown = false
    private var cacheValid = true
    private var inputKnown = false
    private var outputKnown = false
    private var stop: String? = null
    private var started = false

    fun decode(json: JSONObject): List<StreamChunk> {
        content?.accept(json)
        return when (json.optString("type")) {
        "message_start" -> {
            check(!started) { "provider_duplicate_message_start" }
            started = true
            usage(json.getJSONObject("message").optJSONObject("usage"))
        }
        "content_block_start" -> blockStart(json)
        "content_block_delta" -> blockDelta(json)
        "message_delta" -> {
            requireStarted()
            val observation = usage(json.optJSONObject("usage"))
            stop = json.getJSONObject("delta").optString("stop_reason").takeUnless { it.isBlank() || it == "null" }
            observation
        }
        "message_stop" -> finish()
        "content_block_stop" -> content?.closedTool(json.getInt("index")).orEmpty()
        "ping" -> emptyList()
        "error" -> throw IOException("provider_stream_error: " +
            json.optJSONObject("error")?.optString("message")?.take(ERROR_LIMIT))
        else -> throw IOException("provider_unknown_anthropic_event")
        }
    }

    private fun blockStart(json: JSONObject): List<StreamChunk> {
            requireStarted()
            val block = json.getJSONObject("content_block")
            return when (block.getString("type")) {
                "thinking", "redacted_thinking" -> {
                    check(content != null) { "provider_unsupported_anthropic_content_block" }
                    emptyList()
                }
                "text" -> listOf(StreamChunk(contentDelta = block.optString("text")))
                "tool_use" -> listOf(StreamChunk(toolCallDelta = ToolCallDelta(
                    index = json.getInt("index"), id = block.getString("id"), name = block.getString("name"),
                    argumentsDelta = block.optJSONObject("input")?.takeIf { it.length() > 0 }?.toString(),
                )))
                else -> throw IOException("provider_unsupported_anthropic_content_block")
            }
        }
    private fun blockDelta(json: JSONObject): List<StreamChunk> {
            requireStarted()
            val delta = json.getJSONObject("delta")
            return when (delta.getString("type")) {
                "thinking_delta", "signature_delta" -> {
                    check(content != null) { "provider_unsupported_anthropic_delta" }
                    emptyList()
                }
                "text_delta" -> listOf(StreamChunk(contentDelta = delta.getString("text")))
                "input_json_delta" -> listOf(StreamChunk(toolCallDelta = ToolCallDelta(
                    index = json.getInt("index"), argumentsDelta = delta.getString("partial_json"),
                )))
                else -> throw IOException("provider_unsupported_anthropic_delta")
            }
        }
    private fun finish(): List<StreamChunk> {
            requireStarted()
            val reason = stop ?: throw IOException("provider_missing_anthropic_stop_reason")
            val state = content?.finish()?.let { listOf(StreamChunk(providerContent = it)) }.orEmpty()
            return state + listOf(StreamChunk(isFinal = true, finishReason = when (reason) {
                "end_turn", "stop_sequence" -> "stop"
                "tool_use" -> "tool_calls"
                "max_tokens" -> "length"
                else -> reason
            }, tokenUsage = observation()))
        }

    private fun observation(): TokenUsage {
            val validCache = inputKnown && cacheKnown && cached in 0..input
            return TokenUsage(inputTokens = input, outputTokens = output, cacheWriteTokens = createdInput,
                totalTokens = input + output, cachedTokens = if (validCache) cached else 0, model = model,
                cacheObservedInputTokens = if (validCache) input else 0,
                cacheUnknownSamples = if (validCache) 0 else 1,
                inputObserved = inputKnown, outputObserved = outputKnown)
        }

    private companion object { const val ERROR_LIMIT = 300 }

    private fun requireStarted() {
        if (!started) throw IOException("provider_missing_anthropic_message_start")
    }

    private fun usage(value: JSONObject?): List<StreamChunk> {
        value ?: return emptyList()
        if (value.has("cache_read_input_tokens")) {
            cached = count(value, "cache_read_input_tokens") ?: 0
            cacheKnown = count(value, "cache_read_input_tokens") != null
            cacheValid = cacheKnown
        }
        if (value.has("input_tokens")) plainInput = count(value, "input_tokens")
        if (value.has("cache_creation_input_tokens")) createdInput = count(value, "cache_creation_input_tokens")
        inputKnown = plainInput != null && createdInput != null && cacheValid
        input = if (inputKnown) requireNotNull(plainInput) + cached + requireNotNull(createdInput) else 0
        if (value.has("output_tokens")) {
            outputKnown = count(value, "output_tokens") != null
            output = count(value, "output_tokens") ?: 0
        }
        // Cumulative snapshots replace the client's latest usage; they are never added per SSE event.
        return listOf(StreamChunk(tokenUsage = observation()))
    }
    private fun count(value: JSONObject, key: String): Long? = (value.opt(key) as? Number)?.let { number ->
        number.toLong().takeIf { it >= 0 && it.toDouble() == number.toDouble() }
    }

}
