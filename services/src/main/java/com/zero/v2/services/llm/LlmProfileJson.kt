package com.zero.v2.services.llm

import org.json.JSONArray
import org.json.JSONObject

internal object LlmProfileJson {
    private const val DEFAULT_CONTEXT = 1_048_576
    private const val DEFAULT_TEMPERATURE = 0.7
    private const val DEFAULT_OUTPUT = 4096
    private const val DEFAULT_THINKING = 1024
    fun toJson(p: LlmProfile): JSONObject = JSONObject().apply {
        put("id", p.id); put("displayName", p.displayName)
        put("baseUrl", p.baseUrl); put("apiKeyRef", p.apiKeyRef)
        p.apiKeyRefFallback?.let { put("apiKeyRefFallback", it) }
        put("modelId", p.modelId); put("kind", p.kind.name)
        put("contextWindow", p.contextWindow); put("enabled", p.enabled)
        put("temperature", p.temperature.toDouble()); put("maxTokens", p.maxTokens)
        put("enableThinking", p.enableThinking); put("reasoningEffort", p.reasoningEffort)
        put("thinkingBudget", p.thinkingBudget); put("voice", p.voice)
        // M2：能力 + 优先级（显式声明，不靠 modelId 推断）
        put("capabilities", JSONArray(p.capabilities.map { it.name }))
        put("priority", p.priority)
        put("tokenPrices", p.tokenPrices.toJson())
        put("localLlamaPreflight", p.localLlamaPreflight)
        put("gatewayMode", p.gatewayMode.name)
        put("cloudflareByok", p.cloudflareByok)
        put("cloudflareGatewayId", p.cloudflareGatewayId)
        put("connectionId", p.connectionId)
        put("contractId", p.contractId)
        put("parameters", JSONObject(p.parameters))
        put("maxOutputTokens", p.maxOutputTokens)
        put("authMode", p.authMode.name)
        put("authHeader", p.authHeader)
    }

    fun fromJson(o: JSONObject): LlmProfile {
        val kind = LlmProfile.Kind.valueOf(o.optString("kind", "CHAT"))
        // M2：旧 profile 无 capabilities 字段 → 按 kind 保守默认填充（不推断 modelId）
        val capabilities = if (o.has("capabilities")) {
            val arr = o.optJSONArray("capabilities")
            (0 until arr.length()).map { i -> ModelCapability.valueOf(arr.getString(i)) }.toSet()
        } else LlmProfile.defaultCapabilities(kind)
        val profile = LlmProfile(
            id = o.optString("id"), displayName = o.optString("displayName"),
            baseUrl = o.optString("baseUrl"), apiKeyRef = o.optString("apiKeyRef"),
            apiKeyRefFallback = if (o.has("apiKeyRefFallback")) o.optString("apiKeyRefFallback") else null,
            modelId = o.optString("modelId"),
            kind = kind,
            contextWindow = o.optInt("contextWindow", DEFAULT_CONTEXT),
            enabled = o.optBoolean("enabled", true),
            temperature = o.optDouble("temperature", DEFAULT_TEMPERATURE).toFloat(),
            maxTokens = o.optInt("maxTokens", DEFAULT_OUTPUT),
            enableThinking = o.optBoolean("enableThinking", true),
            reasoningEffort = o.optString("reasoningEffort", "high"),
            thinkingBudget = o.optInt("thinkingBudget", DEFAULT_THINKING),
            voice = o.optString("voice", "mimo_default"),
            capabilities = capabilities,
            priority = o.optInt("priority", 0),
            tokenPrices = ModelTokenPrices.fromJson(o.optJSONObject("tokenPrices")),
            localLlamaPreflight = o.optBoolean("localLlamaPreflight", o.optString("id") == "local-qwen"),
            gatewayMode = GatewayMode.valueOf(o.optString("gatewayMode", "DIRECT")),
            cloudflareByok = o.optBoolean("cloudflareByok", false),
            cloudflareGatewayId = o.optString("cloudflareGatewayId", ""),
            connectionId = o.optString("connectionId", ""),
            contractId = o.optString("contractId", "generic-v1"),
            parameters = o.optJSONObject("parameters")?.let { values ->
                values.keys().asSequence().associateWith { values.getString(it) }
            } ?: emptyMap(),
            maxOutputTokens = o.optInt("maxOutputTokens", LlmProfile.MAX_TOKENS_MAX),
            authMode = ConnectionAuth.valueOf(o.optString("authMode", "BEARER")),
            authHeader = o.optString("authHeader", "Authorization"))
        return if (o.has("contractId")) profile else LegacyModelContract.migrate(profile)
    }
}
