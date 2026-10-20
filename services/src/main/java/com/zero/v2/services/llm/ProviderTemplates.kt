package com.zero.v2.services.llm

/**
 * Connection-only starting points. Never infer a model version, capabilities or thinking contract.
 */
data class ProviderTemplate(
    val id: String,
    val label: String,
    val baseUrl: String,
    val auth: ConnectionAuth = ConnectionAuth.BEARER,
    val mode: GatewayMode = GatewayMode.DIRECT,
    val llamaPreflight: Boolean = false,
    val authHeader: String = "X-API-Key",
) {
    fun connection(newId: String): ConnectionProfile {
        require(newId.isNotBlank())
        return ConnectionProfile(
            "connection:$newId",
            baseUrl,
            "llm_connection:$newId",
            authMode = auth,
            authHeader = authHeader,
            gatewayMode = mode,
            cloudflareByok = mode == GatewayMode.CF_BYOK,
            cloudflareGatewayId =
                if (mode in setOf(GatewayMode.CF_BYOK, GatewayMode.CF_GATEWAY)) "default" else "")
    }
}

object ProviderTemplates {
    private const val CF_ROOT = "https://api.cloudflare.com/client/v4/accounts/{account}/ai/v1"
    val all =
        listOf(
            ProviderTemplate("custom", "自定义 OpenAI 兼容服务", ""),
            ProviderTemplate("openai", "OpenAI Chat Completions", "https://api.openai.com/v1"),
            ProviderTemplate("deepseek", "DeepSeek", "https://api.deepseek.com"),
            ProviderTemplate(
                "dashscope",
                "百炼（填写工作空间与地域）",
                "https://{WorkspaceId}.cn-beijing.maas.aliyuncs.com/compatible-mode/v1"),
            ProviderTemplate("siliconflow", "硅基流动", "https://api.siliconflow.cn/v1"),
            ProviderTemplate("zhipu", "智谱（标准 API）", "https://open.bigmodel.cn/api/paas/v4"),
            ProviderTemplate("moonshot", "Kimi / Moonshot", "https://api.moonshot.cn/v1"),
            ProviderTemplate("minimax", "MiniMax 国际站", "https://api.minimax.io/v1"),
            ProviderTemplate(
                "doubao", "火山方舟（标准 API，北京）", "https://ark.cn-beijing.volces.com/api/v3"),
            ProviderTemplate(
                "gemini",
                "Gemini OpenAI 兼容接口",
                "https://generativelanguage.googleapis.com/v1beta/openai"),
            ProviderTemplate(
                "gemini-native",
                "Gemini 原生（须选原生契约）",
                "https://generativelanguage.googleapis.com/v1beta",
                ConnectionAuth.HEADER,
                authHeader = "x-goog-api-key"),
            ProviderTemplate("openrouter", "OpenRouter", "https://openrouter.ai/api/v1"),
            ProviderTemplate(
                "workers", "Cloudflare Workers AI", CF_ROOT, mode = GatewayMode.CF_WORKERS),
            ProviderTemplate(
                "byok", "Cloudflare Gateway 默认 BYOK", CF_ROOT, mode = GatewayMode.CF_BYOK),
            ProviderTemplate(
                "gateway", "Cloudflare Gateway 服务端计费策略", CF_ROOT, mode = GatewayMode.CF_GATEWAY),
            ProviderTemplate(
                "compat",
                "Cloudflare 旧 compat BYOK",
                "https://gateway.ai.cloudflare.com/v1/{account}/{gateway}/compat",
                mode = GatewayMode.CF_BYOK),
            ProviderTemplate(
                "ollama", "Ollama 本地", "http://127.0.0.1:11434/v1", ConnectionAuth.NONE),
            ProviderTemplate(
                "vllm", "vLLM 本地（填写部署地址）", "http://127.0.0.1:8000/v1", ConnectionAuth.NONE),
            ProviderTemplate(
                "llamacpp",
                "llama.cpp 本地（填写部署地址）",
                "http://127.0.0.1:8080/v1",
                ConnectionAuth.NONE,
                llamaPreflight = true),
            ProviderTemplate(
                "lmstudio", "LM Studio 本地", "http://127.0.0.1:1234/v1", ConnectionAuth.NONE),
        )
}
