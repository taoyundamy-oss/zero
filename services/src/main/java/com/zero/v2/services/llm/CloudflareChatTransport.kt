package com.zero.v2.services.llm

import java.net.URI
import okhttp3.Request
import org.json.JSONObject

/** Explicit BYOK opt-in; never infer credentials from a display name or profile id. */
object CloudflareChatTransport {
    private fun isCompat(url: String): Boolean =
        URI(url).host == "gateway.ai.cloudflare.com" &&
            Regex("/v1/[^/]+/[^/]+/compat").matches(URI(url).path.trimEnd('/'))

    fun validationError(profile: LlmProfile): String? {
        val mode = profile.effectiveGatewayMode()
        if (mode == GatewayMode.DIRECT) return null
        val uri = runCatching { URI(profile.baseUrl) }.getOrNull()
        return when {
            uri == null -> "Cloudflare 地址无效。"
            profile.localLlamaPreflight -> "Cloudflare 连接不能启用 llama.cpp 上下文预检。"
            profile.authMode != ConnectionAuth.BEARER -> "Cloudflare 连接须使用 BEARER 认证。"
            !safeUrl(uri) || !supportedUrl(profile.baseUrl) ->
                "请使用 Cloudflare /ai/v1 或 /v1/账户/Gateway/compat 根地址。"
            mode == GatewayMode.CF_WORKERS && isCompat(profile.baseUrl) ->
                "Workers AI 模式使用账户 REST /ai/v1 根地址。"
            else -> modelAndGatewayError(profile, mode)
        }
    }

    private fun modelAndGatewayError(profile: LlmProfile, mode: GatewayMode): String? {
        val workers = mode == GatewayMode.CF_WORKERS
        val workerModel =
            profile.modelId.startsWith("@") || profile.modelId.startsWith("workers-ai/")
        val needsGateway =
            !isCompat(profile.baseUrl) && (!workers || profile.cloudflareGatewayId.isNotBlank())
        return when {
            workers && !Regex("@[^/\\s]+/[^/\\s]+(?:/[^/\\s]+)*").matches(profile.modelId) ->
                "Workers 模型须使用目录中的完整 ID，例如 @cf/提供方/模型；不要添加 workers-ai/ 前缀。"
            !workers && !Regex("[^/\\s]+/[^\\s]+").matches(profile.modelId) ->
                "模型 ID 须包含此入口要求的提供方前缀，请从网关目录复制完整 ID。"
            // Legacy boolean also served as a gateway switch for Workers; retain that working
            // route.
            profile.gatewayMode == GatewayMode.CF_BYOK && workerModel ->
                "Workers AI 模型请选择 Workers AI 模式；BYOK 用于第三方模型密钥。"
            needsGateway && !Regex("[a-zA-Z0-9_-]+").matches(profile.cloudflareGatewayId) ->
                "请填写 Gateway ID（默认网关填 default；Workers 直连可留空）。"
            else -> null
        }
    }

    private fun safeUrl(uri: URI): Boolean =
        uri.scheme == "https" &&
            uri.rawQuery == null &&
            uri.rawFragment == null &&
            uri.userInfo == null

    private fun supportedUrl(url: String): Boolean =
        isCompat(url) ||
            (URI(url).host == "api.cloudflare.com" &&
                Regex("/client/v4/accounts/[^/]+/ai/v1").matches(URI(url).path.trimEnd('/')))

    fun request(profile: LlmProfile, key: String?, stream: Boolean = false): Request.Builder {
        require(validationError(profile) == null) { validationError(profile).orEmpty() }
        return when {
            GeminiNativeProtocol.enabled(profile) ->
                GeminiNativeProtocol.request(profile, key, stream)
            AnthropicNativeProtocol.enabled(profile) ->
                AnthropicNativeProtocol.request(profile, key)
            else -> compatibleRequest(profile, key)
        }
    }

    private fun compatibleRequest(profile: LlmProfile, key: String?): Request.Builder {
        val endpoint =
            if (ResponsesProtocol.enabled(profile)) {
                ResponsesProtocol.validate(profile)
                "responses"
            } else "chat/completions"
        val builder = Request.Builder().url("${profile.baseUrl.trimEnd('/')}/$endpoint")
        require(credentialReady(profile, key)) { "API Key 未配置" }
        val mode = profile.effectiveGatewayMode()
        if (profile.authMode == ConnectionAuth.NONE) {
            require(mode == GatewayMode.DIRECT) { "Cloudflare 需要网关凭据" }
        } else if (profile.authMode == ConnectionAuth.HEADER) {
            require(mode == GatewayMode.DIRECT) { "Cloudflare 使用专用认证" }
            require(profile.authHeader.matches(Regex("[A-Za-z0-9-]+"))) { "认证头名称无效" }
            builder.header(profile.authHeader, requireNotNull(key))
        } else if (mode != GatewayMode.DIRECT && isCompat(profile.baseUrl)) {
            builder.header("cf-aig-authorization", "Bearer $key")
        } else {
            builder.header("Authorization", "Bearer $key")
            if (mode != GatewayMode.DIRECT && profile.cloudflareGatewayId.isNotBlank()) {
                builder.header("cf-aig-gateway-id", profile.cloudflareGatewayId)
            }
        }
        return builder
    }

    fun credentialReady(profile: LlmProfile, key: String?): Boolean =
        (profile.authMode == ConnectionAuth.NONE &&
            profile.effectiveGatewayMode() == GatewayMode.DIRECT) || !key.isNullOrBlank()

    /** Parameter contracts are independent of gateway authentication and profile identity. */
    fun applyContract(body: JSONObject, profile: LlmProfile, stream: Boolean) {
        ModelContracts.get(profile.contractId).apply(body, profile, stream)
    }
}
