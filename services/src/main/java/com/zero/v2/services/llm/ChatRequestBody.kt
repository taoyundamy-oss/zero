package com.zero.v2.services.llm

import com.zero.v2.core.common.ChatMessage
import com.zero.v2.core.common.FunctionDefinition
import com.zero.v2.core.common.MessageRole
import com.zero.v2.core.common.ToolSchemaCompactor
import org.json.JSONArray
import org.json.JSONObject

/** Stable Agent transcript encoding shared by sync, streaming and bounded requests. */
internal object ChatRequestBody {
    private val codec = OpenAiCompatibleCodec()

    fun build(
        profile: LlmProfile, system: String?, messages: List<ChatMessage>,
        stream: Boolean, functions: List<FunctionDefinition>,
    ): JSONObject {
        val native = when {
            GeminiNativeProtocol.enabled(profile) -> GeminiNativeProtocol.body(profile, system, messages, functions)
            ResponsesProtocol.enabled(profile) -> ResponsesProtocol.body(profile, system, messages, stream, functions)
            AnthropicNativeProtocol.enabled(profile) ->
                AnthropicNativeProtocol.body(profile, system, messages, stream, functions)
            else -> null
        }
        if (native != null) return native
        val body = JSONObject().put("model", profile.modelId).put("stream", stream)
            .put("temperature", profile.temperature.toDouble()).put("max_tokens", profile.maxTokens)
        val transcript = JSONArray()
        system?.let { transcript.put(JSONObject().put("role", "system").put("content", it)) }
        messages.forEach { transcript.put(message(profile, it)) }
        body.put("messages", transcript)
        if (stream && functions.isNotEmpty()) body.put("tools", tools(functions))
        CloudflareChatTransport.applyContract(body, profile, stream)
        return body
    }

    private fun message(profile: LlmProfile, msg: ChatMessage): JSONObject {
        val json = JSONObject().put("role", msg.role.name.lowercase())
        content(json, profile, msg)
        ReasoningReplay.apply(json, msg, ModelContracts.get(profile.contractId).replayReasoning &&
            profile.parameters["thinking"] != "false")
        msg.toolCallId?.let { json.put("tool_call_id", it) }
        if (msg.toolCalls.isNotEmpty()) {
            json.put("tool_calls", JSONArray(msg.toolCalls.map { call ->
                JSONObject().put("id", call.id).put("type", "function").put("function",
                    JSONObject().put("name", call.name).put("arguments", call.arguments))
                    .also { ProviderToolState.replay(it, call.providerState, profile) }
            }))
        }
        return json
    }

    private fun content(target: JSONObject, profile: LlmProfile, msg: ChatMessage) {
        if (msg.attachments.isEmpty()) {
            msg.content?.let { target.put("content", it) }
        } else {
            val imageInput = codec.supportsImageInput(profile)
            val items = codec.messageContent(msg, imageInput)
            val value = if (imageInput && msg.role == MessageRole.USER) {
                JSONArray(items.map { JSONObject(it) })
            } else items.joinToString("\n") { it["text"].toString() }
            target.put("content", value)
        }
    }

    private fun tools(functions: List<FunctionDefinition>): JSONArray =
        JSONArray(functions.sortedBy { it.name }.map { f ->
        JSONObject().put("type", "function").put("function", JSONObject()
            .put("name", f.name).put("description", f.description)
            .put("parameters", JSONObject(ToolSchemaCompactor.compact(f.parameters))))
    })
}
