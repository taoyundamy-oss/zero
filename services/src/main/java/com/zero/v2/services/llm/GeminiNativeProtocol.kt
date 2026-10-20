package com.zero.v2.services.llm

import com.zero.v2.core.common.ProviderContentState
import com.zero.v2.core.common.ToolCallDelta
import com.zero.v2.core.common.StreamChunk
import com.zero.v2.core.common.ChatMessage
import com.zero.v2.core.common.FunctionDefinition
import com.zero.v2.core.common.MessageRole
import com.zero.v2.core.common.ToolSchemaCompactor
import java.net.URI
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

/** Explicit Google route. No inference from display names and no automatic migration. */
internal object GeminiNativeProtocol {
    private const val HTTPS_PORT = 443

    fun enabled(p: LlmProfile) = p.contractId == "gemini-native-v1"

    fun validate(p: LlmProfile) {
        val uri = URI(p.baseUrl)
        require(
            p.effectiveGatewayMode() == GatewayMode.DIRECT &&
                uri.scheme == "https" &&
                uri.host == "generativelanguage.googleapis.com" &&
                uri.path.trimEnd('/') == "/v1beta" &&
                uri.rawQuery == null &&
                uri.fragment == null &&
                uri.userInfo == null &&
                uri.port in setOf(-1, HTTPS_PORT) &&
                p.authMode == ConnectionAuth.HEADER &&
                p.authHeader.equals("x-goog-api-key", true) &&
                p.modelId.removePrefix("models/").matches(Regex("[A-Za-z0-9._-]+"))) {
                "Gemini原生须使用官方v1beta根地址、模型ID和x-goog-api-key认证头"
            }
        require(!(p.parameters.containsKey("effort") && p.parameters.containsKey("budget"))) {
            "Gemini思考级别与预算不能同时设置"
        }
    }

    fun request(p: LlmProfile, key: String?, stream: Boolean): Request.Builder {
        validate(p)
        require(!key.isNullOrBlank()) { "Gemini API Key未配置" }
        val method = if (stream) "streamGenerateContent?alt=sse" else "generateContent"
        return Request.Builder()
            .url("${p.baseUrl.trimEnd('/')}/models/${p.modelId.removePrefix("models/")}:$method")
            .header("x-goog-api-key", key)
    }

    fun body(
        p: LlmProfile,
        system: String?,
        messages: List<ChatMessage>,
        functions: List<FunctionDefinition>
    ) = GeminiNativeBody.body(p, system, messages, functions)

    fun objects(a: JSONArray) = (0 until a.length()).map(a::getJSONObject)

    fun text(parts: JSONArray) =
        objects(parts)
            .filter { it.has("text") && !it.optBoolean("thought") }
            .joinToString("") { it.getString("text") }

    fun usage(raw: JSONObject): JSONObject =
        JSONObject().apply {
            if (raw.has("promptTokenCount")) put("prompt_tokens", raw.get("promptTokenCount"))
            if (raw.has("cachedContentTokenCount"))
                put("prompt_cache_hit_tokens", raw.get("cachedContentTokenCount"))
            fun count(key: String): Long? =
                (raw.opt(key) as? Number)?.let { value ->
                    value.toLong().takeIf { it >= 0 && it.toDouble() == value.toDouble() }
                }
            val output = count("candidatesTokenCount")
            val thoughts = if (raw.has("thoughtsTokenCount")) count("thoughtsTokenCount") else 0L
            if (output != null && thoughts != null && output <= Long.MAX_VALUE - thoughts)
                put("completion_tokens", output + thoughts)
            if (raw.has("totalTokenCount")) put("total_tokens", raw.get("totalTokenCount"))
        }

    fun normalize(json: JSONObject): JSONObject {
        if (!json.has("candidates") && !json.has("promptFeedback")) return json
        val candidates = json.optJSONArray("candidates")
        val candidate = candidates?.optJSONObject(0)
        if (candidates?.length() != 1 || candidate?.optString("finishReason") != "STOP") {
            throw GeminiCompletionFailure(json.optJSONObject("usageMetadata"))
        }
        val parts = candidate.getJSONObject("content").getJSONArray("parts")
        if (objects(parts).any { part -> part.keys().asSequence().any {
                it !in setOf("text", "thought", "thoughtSignature")
            } }) throw GeminiCompletionFailure(json.optJSONObject("usageMetadata"))
        return JSONObject()
            .put(
                "choices",
                JSONArray()
                    .put(
                        JSONObject()
                            .put(
                                "message",
                                JSONObject()
                                    .put(
                                        "content",
                                        text(
                                            candidate
                                                .getJSONObject("content")
                                                .getJSONArray("parts"))))))
            .put("usage", usage(json.optJSONObject("usageMetadata") ?: JSONObject()))
    }
}

internal class GeminiNativeStream(private val model: String, private val scope: String) {
    private val parts = JSONArray()
    private val responseId = java.util.UUID.randomUUID().toString()
    var complete = false
        private set

    fun decode(json: JSONObject): List<StreamChunk> = buildList {
        require(!complete) { "gemini_event_after_terminal" }
        require(!json.has("error")) { "gemini_provider_error" }
        json.optJSONObject("usageMetadata")?.let {
            add(StreamChunk(tokenUsage = ProviderUsage.read(GeminiNativeProtocol.usage(it), model)))
        }
        val candidates = json.optJSONArray("candidates") ?: error("gemini_missing_candidates")
        require(candidates.length() == 1) { "gemini_candidate_count" }
        val candidate = candidates.getJSONObject(0)
        candidate.optJSONObject("content")?.optJSONArray("parts")?.let { chunk ->
            GeminiNativeProtocol.objects(chunk).forEach { part ->
                require(
                    part.keys().asSequence().all {
                        it in setOf("text", "thought", "thoughtSignature", "functionCall")
                    } &&
                        (part.has("text") ||
                            part.has("functionCall") ||
                            part.has("thoughtSignature"))) {
                        "gemini_output_part_unsupported"
                    }
                part.optJSONObject("functionCall")?.let { call ->
                    require(call.keys().asSequence().all { it in setOf("name", "args", "id") }) {
                        "gemini_partial_arguments_unsupported"
                    }
                }
                parts.put(JSONObject(part.toString()))
                if (part.has("text"))
                    add(
                        if (part.optBoolean("thought"))
                            StreamChunk(thinkingDelta = part.getString("text"))
                        else StreamChunk(contentDelta = part.getString("text")))
            }
        }
        if (candidate.has("finishReason")) {
            addAll(terminal(candidate))
        }
    }

    private fun terminal(candidate: JSONObject): List<StreamChunk> = buildList {
        val reason = candidate.getString("finishReason")
        require(reason in setOf("STOP", "MAX_TOKENS")) { "gemini_finish_$reason" }
        val calls =
            GeminiNativeProtocol.objects(parts).mapNotNull { it.optJSONObject("functionCall") }
        require(reason == "STOP" || calls.isEmpty()) { "gemini_incomplete_tool_call" }
        val ids = calls.mapNotNull { it.optString("id").takeIf(String::isNotBlank) }
        require(ids.distinct().size == ids.size) { "gemini_duplicate_tool_id" }
        calls.forEachIndexed { i, call ->
            add(
                StreamChunk(
                    toolCallDelta =
                        ToolCallDelta(
                            index = i,
                            id = call.optString("id").ifBlank { "gemini-$responseId-$i" },
                            name = call.getString("name"),
                            argumentsDelta =
                                (call.optJSONObject("args") ?: JSONObject()).toString())))
        }
        add(StreamChunk(providerContent = ProviderContentState(scope, parts.toString())))
        add(
            StreamChunk(
                isFinal = true,
                finishReason =
                    if (reason == "MAX_TOKENS") "length"
                    else if (calls.isNotEmpty()) "tool_calls" else "stop"))
        complete = true
    }
}

private fun canonical(value: Any?): String =
    when (value) {
        is JSONObject ->
            value.keys().asSequence().sorted().joinToString(prefix = "{", postfix = "}") {
                JSONObject.quote(it) + ":" + canonical(value.get(it))
            }
        is JSONArray ->
            (0 until value.length()).joinToString(prefix = "[", postfix = "]") {
                canonical(value.get(it))
            }
        else -> JSONObject.valueToString(value)
    }

private object GeminiNativeBody {
    fun body(
        p: LlmProfile,
        system: String?,
        messages: List<ChatMessage>,
        functions: List<FunctionDefinition>
    ): JSONObject {
        ModelContracts.get(p.contractId).validate(p)
        val contents = JSONArray()
        val systems = mutableListOf<String>()
        system?.let(systems::add)
        val names = messages.flatMap { it.toolCalls }.associate { it.id to it.name }
        val nativeIds = nativeIds(messages, p)
        for (m in messages) {
            // Only leading static snapshots belong to systemInstruction. Historical turn context
            // stays in order.
            if (m.role == MessageRole.SYSTEM &&
                contents.length() == 0 &&
                m.systemSource == "memory_context") {
                m.content?.let(systems::add)
                continue
            }
            val parts = messageParts(m, p, names, nativeIds)
            contents.put(
                JSONObject()
                    .put("role", if (m.role == MessageRole.ASSISTANT) "model" else "user")
                    .put("parts", parts))
        }
        return assembleBody(p, contents, systems, functions)
    }

    private fun assembleBody(
        p: LlmProfile,
        contents: JSONArray,
        systems: List<String>,
        functions: List<FunctionDefinition>
    ): JSONObject {
        val config =
            JSONObject()
                .put("maxOutputTokens", p.maxTokens)
                .put("temperature", p.temperature.toDouble())
        val thinking = JSONObject()
        p.parameters["effort"]?.let { thinking.put("thinkingLevel", it) }
        p.parameters["budget"]?.let { thinking.put("thinkingBudget", it.toInt()) }
        if (thinking.length() > 0) config.put("thinkingConfig", thinking)
        return JSONObject().put("contents", contents).put("generationConfig", config).apply {
            if (systems.isNotEmpty())
                put(
                    "systemInstruction",
                    JSONObject()
                        .put(
                            "parts",
                            JSONArray()
                                .put(JSONObject().put("text", systems.joinToString("\n\n")))))
            if (functions.isNotEmpty())
                put(
                    "tools",
                    JSONArray()
                        .put(
                            JSONObject()
                                .put(
                                    "functionDeclarations",
                                    JSONArray(
                                        functions
                                            .sortedBy { it.name }
                                            .map {
                                                JSONObject()
                                                    .put("name", it.name)
                                                    .put("description", it.description)
                                                    .put(
                                                        "parametersJsonSchema",
                                                        JSONObject(
                                                            ToolSchemaCompactor.compact(
                                                                it.parameters)))
                                            }))))
        }
    }

    private fun messageParts(
        m: ChatMessage,
        p: LlmProfile,
        names: Map<String, String>,
        nativeIds: Set<String>
    ): JSONArray {
        val parts =
            when {
                m.role == MessageRole.ASSISTANT &&
                    m.providerContent?.scope == ProviderToolState.scope(p) -> {
                    replay(m)
                }
                m.role == MessageRole.TOOL ->
                    JSONArray()
                        .put(
                            JSONObject()
                                .put(
                                    "functionResponse",
                                    JSONObject()
                                        .put(
                                            "name",
                                            requireNotNull(names[m.toolCallId] ?: m.toolCallName) {
                                                "gemini_tool_name_missing"
                                            })
                                        .put(
                                            "response",
                                            JSONObject().put("result", m.content.orEmpty()))
                                        .apply {
                                            if (m.toolCallId in nativeIds) put("id", m.toolCallId)
                                        }))
                else -> ordinaryParts(m, p)
            }
        return parts
    }

    private fun nativeIds(messages: List<ChatMessage>, p: LlmProfile): Set<String> {
        val nativeIds =
            messages
                .filter {
                    it.role == MessageRole.ASSISTANT &&
                        it.providerContent?.scope == ProviderToolState.scope(p)
                }
                .flatMap {
                    GeminiNativeProtocol.objects(JSONArray(it.providerContent!!.contentJson))
                }
                .mapNotNull {
                    it.optJSONObject("functionCall")?.optString("id")?.takeIf(String::isNotBlank)
                }
                .toSet()
        return nativeIds
    }

    private fun ordinaryParts(m: ChatMessage, p: LlmProfile): JSONArray =
        JSONArray().apply {
            OpenAiCompatibleCodec()
                .messageContent(m, OpenAiCompatibleCodec().supportsImageInput(p))
                .forEach { item ->
                    if (item["type"] == "image_url") {
                        val url = (item["image_url"] as Map<*, *>)["url"].toString()
                        require(url.startsWith("data:image/") && ";base64," in url) {
                            "gemini_image_requires_restored_base64"
                        }
                        put(
                            JSONObject()
                                .put(
                                    "inlineData",
                                    JSONObject()
                                        .put(
                                            "mimeType",
                                            url.substringAfter("data:").substringBefore(';'))
                                        .put("data", url.substringAfter(";base64,"))))
                    } else put(JSONObject().put("text", item["text"].toString()))
                }
            m.toolCalls.forEach {
                put(
                    JSONObject()
                        .put(
                            "functionCall",
                            JSONObject()
                                .put("name", it.name)
                                .put("args", JSONObject(it.arguments))))
            }
        }

    private fun replay(m: ChatMessage): JSONArray {
        val native = JSONArray(m.providerContent!!.contentJson)
        require(GeminiNativeProtocol.text(native) == m.content.orEmpty()) {
            "gemini_replay_text_mismatch"
        }
        val calls =
            GeminiNativeProtocol.objects(native).mapNotNull { it.optJSONObject("functionCall") }
        require(calls.size == m.toolCalls.size) { "gemini_replay_calls_mismatch" }
        calls.zip(m.toolCalls).forEach { (call, tool) ->
            require(
                call.getString("name") == tool.name &&
                    canonical(call.optJSONObject("args") ?: JSONObject()) ==
                        canonical(JSONObject(tool.arguments))) {
                    "gemini_replay_arguments_mismatch"
                }
            if (call.has("id"))
                require(call.getString("id") == tool.id) { "gemini_replay_id_mismatch" }
        }
        return native
    }
}

internal class GeminiCompletionFailure(rawUsage: JSONObject?) : java.io.IOException("gemini_incomplete_response") {
    val usage = rawUsage?.let { ProviderUsage.read(GeminiNativeProtocol.usage(it)) }
}
