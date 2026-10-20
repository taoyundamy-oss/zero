package com.zero.v2.services.llm

import org.json.JSONObject

enum class ParameterType {
    BOOLEAN,
    INTEGER,
    ENUM
}

data class ParameterSpec(
    val key: String,
    val label: String,
    val path: List<String>,
    val type: ParameterType,
    val values: List<String> = emptyList(),
    val minimum: Int = 1,
    val maximum: Int = LlmProfile.MAX_TOKENS_MAX,
) {
    fun parse(raw: String): Any =
        when (type) {
            ParameterType.BOOLEAN ->
                requireNotNull(raw.toBooleanStrictOrNull()) { "$label 须为 true 或 false" }
            ParameterType.INTEGER ->
                requireNotNull(raw.toIntOrNull()?.takeIf { it in minimum..maximum }) {
                    "$label 须为 $minimum–$maximum 的整数"
                }
            ParameterType.ENUM ->
                raw.also { require(it in values) { "$label 支持：${values.joinToString()}" } }
        }
}

/** Versioned wire contract. Neither a model name nor a gateway selects parameter support. */
data class ModelContract(
    val id: String,
    val label: String,
    val parameters: List<ParameterSpec> = emptyList(),
    val outputField: String = "max_tokens",
    val temperature: Boolean = true,
    val replayReasoning: Boolean = false,
) {
    fun validate(profile: LlmProfile) {
        if (GeminiNativeProtocol.enabled(profile)) GeminiNativeProtocol.validate(profile)
        if (ResponsesProtocol.enabled(profile)) ResponsesProtocol.validate(profile)
        if (id == "cf-anthropic-native-v1") AnthropicNativeProtocol.validate(profile)
        require(
            profile.maxTokens in 1..profile.maxOutputTokens &&
                profile.maxTokens < profile.contextWindow) {
                "回复上限须为正整数，不超过模型输出限制且小于上下文容量"
            }
        require(profile.temperature.isFinite() && profile.temperature in 0f..2f) { "温度须为 0–2" }
        profile.parameters.forEach { (key, value) ->
            val spec = requireNotNull(parameters.find { it.key == key }) { "契约 $id 不支持参数 $key" }
            spec.parse(value)
        }
        require(
            profile.parameters["thinking"] != "false" ||
                profile.parameters.keys.none { it == "budget" || it == "effort" }) {
                "关闭思考时请清空思考预算和强度；空值表示不发送"
            }
        if (id == "zhipu-thinking-v1" &&
            profile.modelId.substringAfterLast('/').lowercase().startsWith("glm-5.3")) {
            require(profile.parameters["thinking"] != "false") { "GLM-5.3系列不支持关闭思考" }
        }
    }

    fun apply(body: JSONObject, profile: LlmProfile, stream: Boolean) {
        validate(profile)
        body.remove("max_tokens")
        body.put(outputField, profile.maxTokens)
        if (!temperature) body.remove("temperature")
        // The legacy contract explicitly preserves its pre-migration synchronous semantics.
        if (id == "deepseek-legacy-v1" && !stream) return
        profile.parameters.forEach { (key, raw) -> applyParameter(body, key, raw) }
    }

    private fun applyParameter(body: JSONObject, key: String, raw: String) {
        val spec = parameters.first { it.key == key }
        val parsed = spec.parse(raw)
        val value =
            if (spec.path == listOf("thinking", "type")) {
                if (parsed == true) "enabled" else "disabled"
            } else parsed
        putPath(body, spec.path, value)
    }

    fun bounded(profile: LlmProfile): LlmProfile {
        val disabled =
            when {
                id in setOf("cf-openai-responses-v1", "openai-responses-v1") -> emptyMap()
                id == "zhipu-thinking-v1" &&
                    profile.modelId.substringAfterLast('/').lowercase().startsWith("glm-5.3") ->
                    emptyMap()
                parameters.any { it.key == "thinking" } -> mapOf("thinking" to "false")
                parameters.any { it.key == "effort" && "none" in it.values } ->
                    mapOf("effort" to "none")
                parameters.any { it.key == "effort" && "low" in it.values } ->
                    mapOf("effort" to "low")
                else -> emptyMap()
            }
        return profile.copy(parameters = disabled)
    }

    private fun putPath(body: JSONObject, path: List<String>, value: Any) {
        var parent = body
        path.dropLast(1).forEach { segment ->
            val child =
                parent.optJSONObject(segment) ?: JSONObject().also { parent.put(segment, it) }
            parent = child
        }
        parent.put(path.last(), value)
    }
}
