package com.zero.v2.services.llm

import com.zero.v2.core.common.ProviderContentState
import com.zero.v2.core.common.StreamChunk
import com.zero.v2.core.common.ToolCallDelta
import org.json.JSONArray
import org.json.JSONObject

/** Commit only complete, ordered blocks. Signatures/redacted data remain opaque. */
internal class AnthropicContentAssembly(private val scope: String) {
    private val blocks = sortedMapOf<Int, JSONObject>()
    private val open = mutableSetOf<Int>()
    private val inputs = mutableMapOf<Int, StringBuilder>()

    fun accept(event: JSONObject) {
        when (event.optString("type")) {
            "content_block_start" -> {
                val index = event.getInt("index")
                require(index == blocks.size && open.isEmpty()) { "provider_content_order_invalid" }
                val block = JSONObject(event.getJSONObject("content_block").toString())
                require(block.getString("type") in setOf("thinking", "redacted_thinking", "text", "tool_use")) {
                    "provider_unsupported_anthropic_content_block"
                }
                blocks[index] = block
                open += index
            }
            "content_block_delta" -> delta(event)
            "content_block_stop" -> {
                val index = event.getInt("index")
                require(open.remove(index)) { "provider_content_stop_invalid" }
                val block = blocks.getValue(index)
                inputs[index]?.let { block.put("input", JSONObject(it.toString())) }
                when (block.getString("type")) {
                    "thinking" -> require(block.getString("signature").isNotEmpty()) {
                        "provider_thinking_signature_missing"
                    }
                    "redacted_thinking" -> require(block.getString("data").isNotEmpty())
                    "tool_use" -> { block.getString("id"); block.getString("name"); block.getJSONObject("input") }
                }
            }
        }
    }

    private fun delta(event: JSONObject) {
        val index = event.getInt("index")
        require(index in open) { "provider_content_delta_without_start" }
        val block = blocks.getValue(index)
        val delta = event.getJSONObject("delta")
        val (kind, field) = when (delta.getString("type")) {
            "thinking_delta" -> "thinking" to "thinking"
            "signature_delta" -> "thinking" to "signature"
            "text_delta" -> "text" to "text"
            "input_json_delta" -> "tool_use" to "partial_json"
            else -> error("provider_unsupported_anthropic_delta")
        }
        require(block.getString("type") == kind) { "provider_content_delta_type_mismatch" }
        if (kind == "tool_use") inputs.getOrPut(index) { StringBuilder() }.append(delta.getString(field))
        else block.put(field, block.optString(field) + delta.getString(field))
    }

    fun finish(): ProviderContentState {
        require(open.isEmpty() && blocks.isNotEmpty()) { "provider_content_incomplete" }
        return ProviderContentState(scope, JSONArray(blocks.values.toList()).toString())
    }

    fun closedTool(index: Int): List<StreamChunk> {
        val block = blocks.getValue(index)
        val emptyInput = block.optJSONObject("input")?.length() == 0
        return if (block.getString("type") == "tool_use" && index !in inputs && emptyInput) {
            listOf(StreamChunk(toolCallDelta = ToolCallDelta(index = index, argumentsDelta = "{}")))
        } else emptyList()
    }
}
