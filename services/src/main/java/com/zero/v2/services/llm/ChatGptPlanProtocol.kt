package com.zero.v2.services.llm

import com.zero.v2.core.common.ChatMessage
import com.zero.v2.core.common.FunctionDefinition
import org.json.JSONArray
import org.json.JSONObject

/** The subscription route is fixed; OAuth credentials never belong to editable connections. */
object ChatGptPlanProtocol {
    const val CONTRACT = "chatgpt-plan-responses-v1"
    const val BASE = "https://api.openai.com/v1"
    const val NAMESPACE = "zero"
    fun enabled(profile: LlmProfile): Boolean = profile.contractId == CONTRACT
    internal fun cacheCredential(profile: LlmProfile, key: String?): String =
        if (enabled(profile)) profile.connectionId else key.orEmpty()

    fun validate(profile: LlmProfile) {
        require(profile.baseUrl == BASE && profile.effectiveGatewayMode() == GatewayMode.DIRECT &&
            profile.authMode == ConnectionAuth.BEARER && !profile.localLlamaPreflight &&
            profile.connectionId.startsWith("oaiapp_") && profile.apiKeyRef.isEmpty() &&
            profile.apiKeyRefFallback == null && profile.tokenPrices.subscription) {
            "ChatGPT套餐须使用独立账号连接、固定官方端点和套餐计量"
        }
    }

    internal fun body(profile: LlmProfile, system: String?, messages: List<ChatMessage>,
        functions: List<FunctionDefinition>): JSONObject {
        ModelContracts.get(CONTRACT).validate(profile)
        val input = JSONArray()
        system?.let { input.put(JSONObject().put("role", "developer").put("content", it)) }
        messages.forEach { ResponsesProtocol.append(input, profile, it) }
        ResponsesProtocol.objects(input).forEach { item ->
            if (item.optString("role") == "system") item.put("role", "developer")
            if (item.optString("type") == "function_call") {
                require(item.optString("namespace", NAMESPACE) == NAMESPACE) { "chatgpt_tool_namespace_mismatch" }
                item.put("namespace", NAMESPACE)
            }
        }
        return JSONObject().put("model", profile.modelId).put("stream", true).put("store", false)
            .put("include", JSONArray().put("reasoning.encrypted_content")).put("input", input).apply {
                profile.parameters["effort"]?.let { put("reasoning", JSONObject().put("effort", it)) }
                if (functions.isNotEmpty()) put("tools", JSONArray().put(JSONObject()
                    .put("type", "namespace").put("name", NAMESPACE).put("description", "Zero local tools")
                    .put("tools", ResponsesProtocol.tools(functions))))
            }
    }
}
