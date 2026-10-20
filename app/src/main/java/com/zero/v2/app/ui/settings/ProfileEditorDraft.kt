package com.zero.v2.app.ui.settings

import com.zero.v2.services.llm.LlmProfile
import java.net.URI

/** Validate the entire draft before either profile or credential callbacks run. */
internal data class ProfileEditorDraft(
    val displayName: String,
    val modelId: String,
    val baseUrl: String,
    val voice: String,
    val temperature: String,
    val maxTokens: String,
    val enableThinking: Boolean,
    val reasoningEffort: String,
    val thinkingBudget: String,
    val cloudflareByok: Boolean = false,
    val cloudflareGatewayId: String = "",
    val authMode: com.zero.v2.services.llm.ConnectionAuth = com.zero.v2.services.llm.ConnectionAuth.BEARER,
    val authHeader: String = "Authorization",
    val contractId: String? = null,
    val parameters: Map<String, String>? = null,
    val contextWindow: String? = null,
    val maxOutputTokens: String? = null,
    val gatewayMode: com.zero.v2.services.llm.GatewayMode? = null,
    val localLlamaPreflight: Boolean? = null,
) {
    fun validationError(profile: LlmProfile): String? {
        if (displayName.isBlank()) return "显示名称不能为空。"
        if (modelId.isBlank()) return "模型 ID 不能为空。"
        val uri = runCatching { URI(baseUrl.trim()) }.getOrNull()
        if (uri?.scheme !in setOf("http", "https") || uri?.host.isNullOrBlank()) {
            return "接口地址须为有效的 HTTP 或 HTTPS 地址。"
        }
        if ((localLlamaPreflight ?: profile.localLlamaPreflight) && (cloudflareByok ||
            (gatewayMode ?: profile.gatewayMode) != com.zero.v2.services.llm.GatewayMode.DIRECT)) {
            return "llama.cpp 上下文预检仅用于直连部署，请关闭后保存网关连接。"
        }
        if (cloudflareByok && authMode != com.zero.v2.services.llm.ConnectionAuth.BEARER) {
            return "Cloudflare BYOK 使用专用网关认证，请选择 BEARER。"
        }
        if (authMode == com.zero.v2.services.llm.ConnectionAuth.HEADER &&
            !authHeader.matches(Regex("[A-Za-z0-9-]+"))) return "认证头名称无效。"
        com.zero.v2.services.llm.CloudflareChatTransport.validationError(profile.copy(
            baseUrl = baseUrl.trim(), modelId = modelId.trim(), cloudflareByok = cloudflareByok,
            cloudflareGatewayId = cloudflareGatewayId.trim(), authMode = authMode,
            gatewayMode = gatewayMode ?: profile.gatewayMode,
            localLlamaPreflight = localLlamaPreflight ?: profile.localLlamaPreflight,
        ))?.let { return it }
        return if (profile.kind == LlmProfile.Kind.CHAT) chatValidationError(profile) else null
    }

    private fun chatValidationError(profile: LlmProfile): String? {
        val temp = temperature.toFloatOrNull()
        if (temp == null || !temp.isFinite() || temp !in 0f..2f) return "温度须为 0–2 的数值。"
        val reply = maxTokens.toIntOrNull() ?: return "回复上限须为整数。"
        val context = contextWindow?.toIntOrNull() ?: profile.contextWindow
        val output = maxOutputTokens?.toIntOrNull() ?: profile.maxOutputTokens
        if (contextWindow != null && contextWindow.toIntOrNull() == null) return "上下文容量须为整数。"
        if (maxOutputTokens != null && maxOutputTokens.toIntOrNull() == null) return "模型输出限制须为整数。"
        return com.zero.v2.services.llm.ModelContracts.validationError(profile.copy(
            modelId = modelId.trim(), baseUrl = baseUrl.trim(), authMode = authMode, authHeader = authHeader,
            gatewayMode = gatewayMode ?: profile.gatewayMode, cloudflareByok = cloudflareByok,
            cloudflareGatewayId = cloudflareGatewayId.trim(),
            localLlamaPreflight = localLlamaPreflight ?: profile.localLlamaPreflight,
            contractId = contractId ?: profile.contractId, parameters = parameters ?: profile.parameters,
            temperature = temp, maxTokens = reply, contextWindow = context, maxOutputTokens = output,
        ))
    }

    fun toProfile(profile: LlmProfile): LlmProfile {
        check(validationError(profile) == null)
        val chat = profile.kind == LlmProfile.Kind.CHAT
        return profile.copy(
            cloudflareByok = cloudflareByok, cloudflareGatewayId = cloudflareGatewayId.trim(),
            authMode = authMode, authHeader = authHeader, gatewayMode = gatewayMode ?: profile.gatewayMode,
            localLlamaPreflight = localLlamaPreflight ?: profile.localLlamaPreflight,
            contractId = contractId ?: profile.contractId, parameters = parameters ?: profile.parameters,
            contextWindow = contextWindow?.toInt() ?: profile.contextWindow,
            maxOutputTokens = maxOutputTokens?.toInt() ?: profile.maxOutputTokens,
            displayName = displayName.trim(), modelId = modelId.trim(), baseUrl = baseUrl.trim(), voice = voice,
            temperature = if (chat) temperature.toFloat() else profile.temperature,
            maxTokens = if (chat) maxTokens.toInt() else profile.maxTokens,
            enableThinking = if (chat) enableThinking else profile.enableThinking,
            reasoningEffort = if (chat && enableThinking) reasoningEffort else profile.reasoningEffort,
            thinkingBudget = profile.thinkingBudget,
        )
    }
}
