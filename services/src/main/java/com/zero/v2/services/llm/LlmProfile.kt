package com.zero.v2.services.llm

/**
 * LLM Profile — 开发前规范化 N2.1（统一 OpenAI 兼容层 · 配置层，D59 同族 / 落地计划 N2.1）。
 *
 * 一个 profile = 一个可调用端点（provider+baseUrl+model+Key+参数差异）。
 * 内置默认档（deepseek/qwen/mimo-tts/qwen-embedding/qwen-vl/qwen-search/mimo-asr，S4.1 后八档），用户可改可增——
 * 新增模型 = 加 profile（零代码）。Key 走 SecureKeyStore（ref→fallback 链沿用既有条目名，零迁移）。
 * 专用存储（LlmProfileStore），不进扁平 @ConfigField（Step 8.3 McpServerStore 先例）。
 * SR9：VISION/SEARCH 为 DashScope 原生协议族（baseUrl 存原生端点根，非 OpenAI 兼容根）。
 */
data class LlmProfile(
    val id: String,                    // 唯一标识（内置：deepseek/qwen/mimo-tts/qwen-embedding/qwen-vl/qwen-search）
    val displayName: String,
    val baseUrl: String,               // OpenAI 兼容根（如 https://api.deepseek.com/v1）
    val apiKeyRef: String,             // SecureKeyStore 主条目名
    val apiKeyRefFallback: String? = null, // 兜底条目名（embedding/vision→llm_client:qwen 既有链）
    val modelId: String = "",
    val kind: Kind = Kind.CHAT,
    val contextWindow: Int = 1_048_576,
    val enabled: Boolean = true,
    // ── 数值参数（§7.3 定界，LlmProfileStore 写入时钳位）──
    val temperature: Float = 0.7f,     // [0, 2]
    val maxTokens: Int = 4096,         // [256, 32768]（模型上下文为硬背板）
    val enableThinking: Boolean = true,
    val reasoningEffort: String = "high", // none/low/high/max（D28 四档）
    val thinkingBudget: Int = 1024,    // Qwen 域 [256, 4096]
    // ── TTS 域（mimo-tts）──
    val voice: String = "mimo_default",
    // ── M2：能力路由（显式声明，不因 modelId 含"vl"自动推断；旧 profile 缺省按 kind 保守填充）──
    val capabilities: Set<ModelCapability> = defaultCapabilities(kind),
    /** 用户优先级（M2 tie-break；高=首选；默认 0）。 */
    val priority: Int = 0,
    val contractId: String = "generic-v1",
    val parameters: Map<String, String> = emptyMap(),
    val maxOutputTokens: Int = MAX_TOKENS_MAX,
    val connectionId: String = "",
    val authMode: ConnectionAuth = ConnectionAuth.BEARER,
    val authHeader: String = "Authorization",
    val cloudflareByok: Boolean = false,
    val cloudflareGatewayId: String = "",
    val gatewayMode: GatewayMode = GatewayMode.DIRECT,
    val tokenPrices: ModelTokenPrices = ModelTokenPrices(),
    val localLlamaPreflight: Boolean = id == "local-qwen",
) {
    enum class Kind { CHAT, TTS, EMBEDDING, VISION, SEARCH, ASR }

    companion object {
        /** Explicit opt-in development endpoint; never silently enable on other installations. */
        fun localQwen(baseUrl: String, modelId: String): LlmProfile = LlmProfile(
            id = "local-qwen", displayName = "本地 Qwen3.5 9B（开发主力）",
            baseUrl = baseUrl.trimEnd('/'), apiKeyRef = "llm_client:local",
            modelId = modelId, contextWindow = 24_576, maxTokens = 2048,
            enableThinking = false, priority = 1000,
            capabilities = setOf(ModelCapability.CHAT, ModelCapability.FUNCTION_CALLING),
        )

        // §7.3 定界常量（store 钳位用）
        const val TEMPERATURE_MIN = 0f
        const val TEMPERATURE_MAX = 2f
        const val MAX_TOKENS_MIN = 256
        const val MAX_TOKENS_MAX = 32768
        const val THINKING_BUDGET_MIN = 256
        const val THINKING_BUDGET_MAX = 4096
        val REASONING_EFFORTS = listOf("none", "low", "high", "max")

        /**
         * 按 kind 的保守默认能力（M2 旧 profile 缺省填充）——不推断 modelId 字符串。
         * CHAT→基础对话+工具+长上下文；VISION→CHAT+IMAGE_INPUT+VISION；SEARCH→CHAT+工具；
         * TTS/EMBEDDING/ASR→空（非 chat 路由，按 kind 选用）。
         */
        fun defaultCapabilities(kind: Kind): Set<ModelCapability> = when (kind) {
            Kind.CHAT -> setOf(ModelCapability.CHAT, ModelCapability.FUNCTION_CALLING, ModelCapability.LONG_CONTEXT)
            Kind.VISION -> setOf(
                ModelCapability.CHAT, ModelCapability.IMAGE_INPUT, ModelCapability.VISION,
                ModelCapability.FUNCTION_CALLING, ModelCapability.LONG_CONTEXT,
            )
            Kind.SEARCH -> setOf(ModelCapability.CHAT, ModelCapability.FUNCTION_CALLING)
            Kind.TTS, Kind.EMBEDDING, Kind.ASR -> emptySet()
        }

        /** 内置默认档（S1.2 后七档；Key 条目名全部沿用既有 SecureKeyStore 链，零迁移）。
         *  M2：每档显式声明 capabilities + priority（不靠 modelId 推断）。 */
        fun defaults(): List<LlmProfile> = listOf(
            LlmProfile(
                id = "deepseek", displayName = "DeepSeek Flash",
                contractId = "deepseek-v1", parameters = mapOf("thinking" to "true", "effort" to "high"),
                baseUrl = "https://api.deepseek.com/v1",
                apiKeyRef = "llm_client:deepseek",
                modelId = "deepseek-flash", kind = Kind.CHAT,
                capabilities = setOf(
                    ModelCapability.CHAT, ModelCapability.REASONING,
                    ModelCapability.IMAGE_INPUT, ModelCapability.VISION,
                    ModelCapability.FUNCTION_CALLING, ModelCapability.LONG_CONTEXT,
                ),
                priority = 100),
            LlmProfile(
                id = "qwen", displayName = "Qwen (DashScope 兼容)",
                contractId = "dashscope-v1", parameters = mapOf("thinking" to "false"),
                baseUrl = "https://dashscope.aliyuncs.com/compatible-mode/v1",
                apiKeyRef = "llm_client:qwen",
                modelId = "qwen3.8-max", kind = Kind.CHAT,
                enableThinking = false,
                capabilities = setOf(
                    ModelCapability.CHAT, ModelCapability.FUNCTION_CALLING, ModelCapability.LONG_CONTEXT,
                ),
                priority = 90),
            LlmProfile(
                id = "mimo-tts", displayName = "MiMo TTS（流式语音合成）",
                baseUrl = "https://api.xiaomimimo.com/v1",
                apiKeyRef = "tts:mimo",
                modelId = "mimo-v2.5-tts", kind = Kind.TTS),
            LlmProfile(
                id = "qwen-embedding", displayName = "Qwen Embedding",
                baseUrl = "https://dashscope.aliyuncs.com/compatible-mode/v1",
                apiKeyRef = "embedding:qwen", apiKeyRefFallback = "llm_client:qwen",
                modelId = "text-embedding-v3", kind = Kind.EMBEDDING),
            // S1.2-a（SR9）：qwen-vl 预置修正——原值是 OpenAI 兼容假想值（compatible-mode/v1 +
            // qwen-vl-max，从未被任何模块消费）；修正为 VisionModule 实际使用的 DashScope 原生
            // 专属实例端点 + qwen3-vl-flash + 模块现行 Key ref（vision_analyze:qwen，零迁移）。
            // M2：显式声明 IMAGE_INPUT/VISION（不因 modelId 含"vl"自动推断）。
            LlmProfile(
                id = "qwen-vl", displayName = "Qwen VL（视觉）",
                baseUrl = "https://ws-example.cn-beijing.maas.aliyuncs.com/api/v1",
                apiKeyRef = "vision_analyze:qwen", apiKeyRefFallback = "llm_client:qwen",
                modelId = "qwen3-vl-flash", kind = Kind.VISION,
                capabilities = setOf(
                    ModelCapability.CHAT, ModelCapability.IMAGE_INPUT, ModelCapability.VISION,
                    ModelCapability.FUNCTION_CALLING, ModelCapability.LONG_CONTEXT,
                ),
                priority = 50),
            // S1.2-a（G12）：搜索模块专属 profile——DashScope 原生 multimodal-generation 端点
            // （enable_search 协议，SR9 例外族）；ref 沿用卡片现行条目（web_search:qwen，零迁移）
            LlmProfile(
                id = "qwen-search", displayName = "Qwen 联网搜索",
                baseUrl = "https://ws-example.cn-beijing.maas.aliyuncs.com/api/v1",
                apiKeyRef = "web_search:qwen", apiKeyRefFallback = "llm_client:qwen",
                modelId = "qwen3.5-flash", kind = Kind.SEARCH,
                capabilities = setOf(ModelCapability.CHAT, ModelCapability.FUNCTION_CALLING),
                priority = 40),
            // S4.1（D121/C2-G4）：ASR 线上通道——与 mimo-tts 同域同 Key（设计：一个 Key 管
            // TTS+ASR；专用 asr:mimo → tts:mimo 回退链，专用条目可单独覆盖）
            LlmProfile(
                id = "mimo-asr", displayName = "MiMo ASR（流式语音识别）",
                baseUrl = "https://api.xiaomimimo.com/v1",
                apiKeyRef = "asr:mimo", apiKeyRefFallback = "tts:mimo",
                modelId = "mimo-v2.5-asr", kind = Kind.ASR)
        )

        /** S1.2-a 预置值升级表：仅"与旧预置逐字段一致"（用户从未编辑）的存量条目会被升级
         *  （见 LlmProfileStore.upgradeLegacyPresets，P1-7：用户改过的保留不动）。 */
        fun legacyPresetUpgrades(): List<Pair<LlmProfile, LlmProfile>> {
            val newVl = defaults().first { it.id == "qwen-vl" }
            val legacyVl = newVl.copy(
                baseUrl = "https://dashscope.aliyuncs.com/compatible-mode/v1",
                apiKeyRef = "vision:qwen",
                modelId = "qwen-vl-max")
            val newDeepSeek = defaults().first { it.id == "deepseek" }
            val legacyDeepSeek = newDeepSeek.copy(
                displayName = "DeepSeek V4 Flash", modelId = "deepseek-v4-flash",
                capabilities = newDeepSeek.capabilities - setOf(ModelCapability.IMAGE_INPUT, ModelCapability.VISION),
            )
            return listOf(legacyVl to newVl, legacyDeepSeek to newDeepSeek)
        }
    }
}
