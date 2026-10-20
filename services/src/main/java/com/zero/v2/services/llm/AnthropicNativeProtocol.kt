package com.zero.v2.services.llm

import com.zero.v2.core.common.ChatMessage
import com.zero.v2.core.common.FunctionDefinition
import com.zero.v2.core.common.MessageRole
import com.zero.v2.core.common.ToolSchemaCompactor
import java.net.URI
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

/** Explicit CF native Messages protocol. Existing OpenAI contracts never enter this branch. */
internal object AnthropicNativeProtocol {
    private const val MIN_THINKING_BUDGET = 1024
    private const val REST_ACCOUNT_INDEX = 3
    fun enabled(profile: LlmProfile) = profile.contractId == "cf-anthropic-native-v1"

    fun validate(profile: LlmProfile) {
        require(profile.effectiveGatewayMode() in setOf(GatewayMode.CF_GATEWAY, GatewayMode.CF_BYOK) &&
            profile.modelId.startsWith("anthropic/")) { "原生Anthropic契约需要CF Gateway/BYOK及anthropic/模型ID" }
        require(CloudflareChatTransport.validationError(profile) == null) { "原生Anthropic需要有效CF连接" }
        val budget = profile.parameters["budget"]?.toIntOrNull()
        val validBudget = budget != null && budget >= MIN_THINKING_BUDGET && budget < profile.maxTokens
        require(if (profile.parameters["thinking"] == "true") validBudget
            else budget == null) { "启用思考须设置至少1024且小于回复上限的预算；关闭时清空预算" }
    }

    fun request(profile: LlmProfile, key: String?): Request.Builder {
        validate(profile)
        require(!key.isNullOrBlank()) { "CF API Key 未配置" }
        val uri = URI(profile.baseUrl)
        val parts = uri.path.trim('/').split('/')
        val account = if (uri.host == "api.cloudflare.com") parts[REST_ACCOUNT_INDEX] else parts[1]
        val gateway = if (uri.host == "api.cloudflare.com") profile.cloudflareGatewayId else parts[2]
        return Request.Builder().url("https://gateway.ai.cloudflare.com/v1/$account/$gateway/anthropic/v1/messages")
            .header("cf-aig-authorization", "Bearer $key").header("anthropic-version", "2023-06-01")
            .apply { if (profile.effectiveGatewayMode() == GatewayMode.CF_BYOK) header("cf-aig-no-wholesale", "true") }
    }

    fun body(profile: LlmProfile, system: String?, messages: List<ChatMessage>, stream: Boolean,
        functions: List<FunctionDefinition>): JSONObject {
        val result = JSONObject().put("model", profile.modelId.removePrefix("anthropic/"))
            .put("stream", stream)
        ModelContracts.get(profile.contractId).apply(result, profile, stream)
        val transcript = JSONArray()
        val systems = mutableListOf<String>()
        system?.takeIf { it.isNotBlank() }?.let(systems::add)
        messages.forEach { msg ->
            if (msg.role == MessageRole.SYSTEM) msg.content?.let(systems::add)
            else {
                require(msg.attachments.isEmpty()) { "anthropic_native_attachments_not_supported" }
                val role = if (msg.role == MessageRole.ASSISTANT) "assistant" else "user"
                val blocks = content(profile, msg)
                append(transcript, role, blocks)
            }
        }
        if (systems.isNotEmpty()) result.put("system", systems.joinToString("\n\n"))
        result.put("messages", transcript)
        if (functions.isNotEmpty()) result.put("tools", JSONArray(functions.sortedBy { it.name }.map {
            JSONObject().put("name", it.name).put("description", it.description)
                .put("input_schema", JSONObject(ToolSchemaCompactor.compact(it.parameters)))
        }))
        return result
    }

    private fun content(profile: LlmProfile, msg: ChatMessage): JSONArray {
        val state = msg.providerContent
        if (msg.role == MessageRole.ASSISTANT && state?.scope == ProviderToolState.scope(profile)) {
                val blocks = JSONArray(requireNotNull(state).contentJson)
                val ordered = (0 until blocks.length()).map { i -> blocks.getJSONObject(i) }
                val calls = ordered.filter { block -> block.getString("type") == "tool_use" }
                require(ordered.filter { it.getString("type") == "text" }.joinToString("") {
                    it.getString("text")
                } == msg.content.orEmpty()) { "provider_content_text_mismatch" }
                require(calls.map { call -> call.getString("id") } == msg.toolCalls.map { call -> call.id }) {
                    "provider_content_tool_mismatch"
                }
                calls.zip(msg.toolCalls).forEach { (block, call) ->
                    require(block.getString("name") == call.name &&
                        canonical(block.getJSONObject("input")) == canonical(JSONObject(call.arguments))) {
                        "provider_content_tool_mismatch"
                    }
                }
                return blocks
        }
        val blocks = JSONArray()
        if (msg.role == MessageRole.TOOL) blocks.put(JSONObject().put("type", "tool_result")
            .put("tool_use_id", requireNotNull(msg.toolCallId)).put("content", msg.content.orEmpty()))
        else {
            msg.content?.takeIf { it.isNotEmpty() }?.let {
                blocks.put(JSONObject().put("type", "text").put("text", it))
            }
            msg.toolCalls.forEach { blocks.put(JSONObject().put("type", "tool_use").put("id", it.id)
                .put("name", it.name).put("input", JSONObject(it.arguments))) }
        }
        return blocks
    }

    private fun append(transcript: JSONArray, role: String, blocks: JSONArray) {
        if (blocks.length() == 0) return
        val previous = transcript.optJSONObject(transcript.length() - 1)
        if (previous?.optString("role") == role) {
            val target = previous.getJSONArray("content")
            for (i in 0 until blocks.length()) target.put(blocks.get(i))
        } else transcript.put(JSONObject().put("role", role).put("content", blocks))
    }

    fun normalizeResponse(json: JSONObject): JSONObject {
        if (json.optString("type") != "message") return json
        val blocks = json.getJSONArray("content")
        val text = (0 until blocks.length()).map { blocks.getJSONObject(it) }
            .filter { it.optString("type") == "text" }.joinToString("") { it.getString("text") }
        val usage = json.optJSONObject("usage") ?: JSONObject()
        val cached = usage.optLong("cache_read_input_tokens")
        val input = usage.optLong("input_tokens") + cached + usage.optLong("cache_creation_input_tokens")
        val output = usage.optLong("output_tokens")
        val choices = JSONArray().put(JSONObject().put("message", JSONObject().put("content", text)))
        return JSONObject().put("choices", choices)
            .put("usage", JSONObject().put("prompt_tokens", input).put("completion_tokens", output)
                .put("total_tokens", input + output).apply {
                    if (usage.has("cache_creation_input_tokens")) {
                        put("cache_creation_input_tokens", usage.get("cache_creation_input_tokens"))
                    }
                    if (usage.opt("input_tokens") !is Number) remove("prompt_tokens")
                    if (usage.has("cache_read_input_tokens")) {
                        put("prompt_cache_hit_tokens", usage.opt("cache_read_input_tokens"))
                    }
                })
    }

    private fun canonical(value: Any?): String = when (value) {
        is JSONObject -> value.keys().asSequence().sorted().joinToString(prefix = "{", postfix = "}") {
            JSONObject.quote(it) + ":" + canonical(value.get(it))
        }
        is JSONArray -> (0 until value.length()).joinToString(prefix = "[", postfix = "]") { canonical(value.get(it)) }
        is String -> JSONObject.quote(value)
        else -> value.toString()
    }
}
