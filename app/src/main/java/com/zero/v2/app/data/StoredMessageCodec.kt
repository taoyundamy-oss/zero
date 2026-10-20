package com.zero.v2.app.data

import com.zero.v2.core.common.ChatMessage
import com.zero.v2.core.common.ToolProviderState
import com.zero.v2.core.common.ToolCall
import org.json.JSONArray
import org.json.JSONObject

/** Versioned wire metadata in the existing tool_calls column; legacy arrays remain readable. */
internal object StoredMessageCodec {
    fun encode(message: ChatMessage): String? {
        if (message.toolCalls.isEmpty() && message.toolCallId == null && message.thinking == null && message.providerContent == null) return null
        return JSONObject().put("version", 3).put("thinking", message.thinking)
            .apply { message.providerContent?.let { state -> put("provider_content", JSONObject()
                .put("version", 1).put("scope", state.scope).put("content", state.contentJson)) } }
            .put("tool_call_id", message.toolCallId).put("tool_name", message.toolCallName)
            .put("calls", JSONArray().apply {
                message.toolCalls.forEach { call -> put(JSONObject().put("id", call.id)
                    .put("name", call.name).put("arguments", call.arguments).put("stream_index", call.streamIndex)
                    .apply { call.providerState?.let { state -> put("provider_state", JSONObject()
                        .put("version", 1).put("scope", state.scope)
                        .put("google_thought_signature", state.googleThoughtSignature)) } }) }
            }).toString()
    }

    fun restore(message: ChatMessage, json: String?): ChatMessage {
        if (json.isNullOrBlank()) return message
        return try {
            val metadata = if (json.trimStart().startsWith("[")) JSONObject().put("calls", JSONArray(json)) else JSONObject(json)
            val calls = metadata.optJSONArray("calls") ?: JSONArray()
            message.copy(
                providerContent = metadata.optJSONObject("provider_content")?.let { state ->
                    if (state.optInt("version") != 1) null else com.zero.v2.core.common.ProviderContentState(
                        state.getString("scope"), state.getString("content"))
                },
                thinking = metadata.optional("thinking"), toolCallId = metadata.optional("tool_call_id"),
                toolCallName = metadata.optional("tool_name"),
                toolCalls = (0 until calls.length()).map { index ->
                    val call = calls.getJSONObject(index)
                    val function = call.optJSONObject("function") ?: call
                    ToolCall(call.getString("id"), function.getString("name"), function.getString("arguments"),
                        providerState = call.optJSONObject("provider_state")?.let { state ->
                            if (state.optInt("version") != 1) null else ToolProviderState(
                                state.getString("scope"), state.getString("google_thought_signature"))
                        }, streamIndex = if (call.isNull("stream_index")) null else call.getInt("stream_index"))
                },
            )
        } catch (_: org.json.JSONException) { message }
    }

    private fun JSONObject.optional(key: String) = if (isNull(key)) null else optString(key).takeIf { it.isNotEmpty() }
}
