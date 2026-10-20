package com.zero.v2.services.llm

import com.zero.v2.core.common.ChatMessage
import com.zero.v2.core.common.FunctionDefinition
import com.zero.v2.core.common.MessageRole
import com.zero.v2.core.common.ToolSchemaCompactor
import java.net.URI
import org.json.JSONArray
import org.json.JSONObject

/** Stateless Responses transport; opaque output items stay in scoped message metadata. */
internal object ResponsesProtocol {
    fun enabled(profile: LlmProfile) =
        ChatGptPlanProtocol.enabled(profile) ||
            profile.contractId in
                setOf("openai-responses-v1", "cf-luna-responses-v1", "cf-openai-responses-v1")

    fun validate(profile: LlmProfile) {
        if (ChatGptPlanProtocol.enabled(profile)) return ChatGptPlanProtocol.validate(profile)
        if (profile.contractId == "openai-responses-v1") {
            validateDirect(profile)
            return
        }
        require(
            profile.effectiveGatewayMode() == GatewayMode.CF_GATEWAY &&
                profile.modelId.startsWith("openai/") &&
                URI(profile.baseUrl).host == "api.cloudflare.com" &&
                CloudflareChatTransport.validationError(profile) == null) {
                "Responses 契约需要 CF Gateway REST /ai/v1 连接及 openai/模型ID"
            }
        require(
            profile.contractId != "cf-luna-responses-v1" ||
                profile.modelId == "openai/gpt-6-luna") {
                "Luna专用契约仅用于openai/gpt-6-luna；其他模型请选择CF OpenAI Responses契约"
            }
    }

    private fun validateDirect(profile: LlmProfile) {
        val uri = URI(profile.baseUrl)
        require(
            profile.effectiveGatewayMode() == GatewayMode.DIRECT &&
                uri.scheme == "https" &&
                uri.host == "api.openai.com" &&
                uri.path.trimEnd('/') == "/v1" &&
                uri.port in setOf(-1, HTTPS_PORT) &&
                uri.userInfo == null &&
                uri.rawQuery == null &&
                uri.fragment == null &&
                profile.authMode == ConnectionAuth.BEARER) {
                "OpenAI直连Responses须使用官方https://api.openai.com/v1及独立API密钥"
            }
    }

    private const val HTTPS_PORT = 443

    fun body(
        profile: LlmProfile,
        system: String?,
        messages: List<ChatMessage>,
        stream: Boolean,
        functions: List<FunctionDefinition>
    ): JSONObject {
        if (ChatGptPlanProtocol.enabled(profile))
            return ChatGptPlanProtocol.body(profile, system, messages, functions)
        val input = JSONArray()
        system?.let { input.put(JSONObject().put("role", "system").put("content", it)) }
        messages.forEach { append(input, profile, it) }
        return JSONObject()
            .put("model", profile.modelId)
            .put("stream", stream)
            .put("store", false)
            .put("include", JSONArray().put("reasoning.encrypted_content"))
            .put("input", input)
            .apply {
                ModelContracts.get(profile.contractId).apply(this, profile, stream)
                if (functions.isNotEmpty()) put("tools", tools(functions))
            }
    }

    fun tools(functions: List<FunctionDefinition>): JSONArray =
        JSONArray(
            functions
                .sortedBy { it.name }
                .map {
                    JSONObject()
                        .put("type", "function")
                        .put("name", it.name)
                        .put("description", it.description)
                        .put("strict", false)
                        .put("parameters", JSONObject(ToolSchemaCompactor.compact(it.parameters)))
                })

    fun append(input: JSONArray, profile: LlmProfile, message: ChatMessage) {
        val state = message.providerContent
        if (message.role == MessageRole.ASSISTANT &&
            state?.scope == ProviderToolState.scope(profile)) {
            val items = JSONArray(requireNotNull(state).contentJson)
            require(text(items) == message.content.orEmpty()) { "responses_replay_text_mismatch" }
            val calls = objects(items).filter { it.getString("type") == "function_call" }
            require(calls.map { it.getString("call_id") } == message.toolCalls.map { it.id }) {
                "responses_replay_calls_mismatch"
            }
            calls.zip(message.toolCalls).forEach { (item, call) ->
                require(
                    item.getString("name") == call.name &&
                        item.getString("arguments") == call.arguments) {
                        "responses_replay_arguments_mismatch"
                    }
            }
            objects(items).forEach(input::put)
        } else if (message.role == MessageRole.TOOL) {
            input.put(
                JSONObject()
                    .put("type", "function_call_output")
                    .put("call_id", requireNotNull(message.toolCallId))
                    .put(
                        "output",
                        OpenAiCompatibleCodec().messageContent(message, false).joinToString("\n") {
                            it["text"].toString()
                        }))
        } else {
            appendContent(input, profile, message)
            message.toolCalls.forEach {
                input.put(
                    JSONObject()
                        .put("type", "function_call")
                        .put("call_id", it.id)
                        .put("name", it.name)
                        .put("arguments", it.arguments))
            }
        }
    }

    private fun appendContent(input: JSONArray, profile: LlmProfile, message: ChatMessage) {
        if (message.content.isNullOrEmpty() && message.attachments.isEmpty()) return
        val codec = OpenAiCompatibleCodec()
        val content =
            if (message.attachments.isEmpty()) message.content
            else
                JSONArray(
                    codec.messageContent(message, codec.supportsImageInput(profile)).map { part ->
                        if (part["type"] == "image_url")
                            JSONObject()
                                .put("type", "input_image")
                                .put("image_url", (part["image_url"] as Map<*, *>)["url"])
                        else
                            JSONObject()
                                .put(
                                    "type",
                                    if (message.role == MessageRole.ASSISTANT) "output_text"
                                    else "input_text")
                                .put("text", part["text"])
                    })
        input.put(JSONObject().put("role", message.role.name.lowercase()).put("content", content))
    }

    fun objects(array: JSONArray): List<JSONObject> =
        (0 until array.length()).map(array::getJSONObject)

    fun text(items: JSONArray): String =
        objects(items).joinToString("") { item ->
            when (item.getString("type")) {
                "reasoning",
                "function_call" -> ""
                "message" ->
                    objects(item.getJSONArray("content")).joinToString("") { part ->
                        when (part.getString("type")) {
                            "output_text" -> part.getString("text")
                            "refusal" -> part.getString("refusal")
                            else -> error("responses_content_unsupported")
                        }
                    }
                else -> error("responses_output_unsupported")
            }
        }

    fun normalizeResponse(json: JSONObject): JSONObject {
        if (json.optString("object") != "response") return json
        if (json.optString("status") != "completed") throw ResponsesFailure(json)
        val usage = json.optJSONObject("usage") ?: JSONObject()
        return JSONObject()
            .put(
                "choices",
                JSONArray()
                    .put(
                        JSONObject()
                            .put(
                                "message",
                                JSONObject().put("content", text(json.getJSONArray("output"))))))
            .put(
                "usage",
                JSONObject()
                    .put("prompt_tokens", usage.opt("input_tokens"))
                    .put("completion_tokens", usage.opt("output_tokens"))
                    .put("total_tokens", usage.opt("total_tokens"))
                    .apply {
                        val details = usage.optJSONObject("input_tokens_details")
                        if (details?.has("cached_tokens") == true) {
                            put("prompt_cache_hit_tokens", details.opt("cached_tokens"))
                        }
                    })
    }
}
