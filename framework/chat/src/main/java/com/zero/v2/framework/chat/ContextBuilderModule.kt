package com.zero.v2.framework.chat

import com.zero.v2.core.common.ChatMessage
import com.zero.v2.core.common.FunctionDefinition
import com.zero.v2.core.common.PromptPlan
import com.zero.v2.core.common.PromptPrefix
import com.zero.v2.core.moduleapi.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 上下文构建器模块。
 *
 * 对标 Alife 的 CharacterSystem + ContextBuilder。
 * 负责组装 System Prompt — 角色人设 + 本轮上下文 + 可用工具。
 *
 * N6.6 世代压缩（取代 v2.2 未实现的"超预算自动压缩历史"规划）：
 * 历史压缩/摘要由 ChatBotModule 主导（append-only + est>90% 预算一次性世代切换），
 * 本模块仅负责 persona/工具表截断（TokenBudgetAllocator）与 contextExtension 组装
 * （早期对话摘要经 ChatBotModule 注入首段）。
 */
/**
 * 上下文构建器配置 — 开发前规范化（设计 §8.3 #3：contextWindow 用户化，原硬编码 256k）。
 * 低于 128k 历史压缩过激（LLM 丢上下文→忘记中段任务，破 UX）；高于 512k 成本↑。
 */
class ContextBuilderConfig : ModuleConfigBase() {
    @ConfigField(
        label = "世代历史预算（Token）", fieldType = ConfigFieldType.NUMBER,
        min = 128_000.0, max = 960_000.0,
        description = "期望保留的当前世代历史预算，默认512k。实际受所选模型容量、输出预留、5%安全余量及系统/工具占位限制。" +
                "历史估算超过生效预算90%时，换代目标为65%。调大可减少换代，但不延长服务商缓存有效期；" +
                "调大后仅继续追加，不把已归档历史重新插回请求。具体设置/生效/触发值见换代运行记录"
    )
    var contextWindow: Int = 512_000

    @ConfigField(
        label = "会话摘要长度上限（字）", fieldType = ConfigFieldType.NUMBER,
        min = 200.0, max = 2000.0,
        description = "早期对话压缩成摘要后的最长字数。默认 600 兼顾信息量与每轮成本；" +
                "调大=早期细节保留更多但每轮固定消耗略增（受总上下文预算约束）"
    )
    var summaryMaxChars: Int = 600

    // Legacy persisted keys remain readable; the production projection preserves full tool results.
    var toolTrimEnabled: Boolean = true
    var toolTrimChars: Int = 500

    // ═══ C2：工具有界发现（灰度开关，默认有界；false=回退旧全量目录）═══
    @ConfigField(
        label = "工具有界发现", fieldType = ConfigFieldType.TOGGLE,
        description = "开启后提供固定核心工具和发现/执行入口，其余工具参数按需分页返回；核心集合不随本轮意图重排。" +
                "关闭则使用全量可用工具目录。默认开启"
    )
    var toolDiscoveryEnabled: Boolean = true

    @ConfigField(
        label = "工具发现每页上限", fieldType = ConfigFieldType.NUMBER,
        min = 4.0, max = 20.0,
        description = "发现工具时单页返回的工具数上限。请求入口本身固定，不随页内容变化"
    )
    var maxTools: Int = 8

    @ConfigField(label = "固定工具名称", fieldType = ConfigFieldType.TEXT,
        description = "按工具真实名称填写，逗号分隔，最多4项；在新会话或下一次自然世代切换后挂载，受工具定义预算限制。")
    var pinnedToolNames: String = ""

    // ═══ R3：自动证据注入（D3——可关，关闭不删除存量；入 turnContext 不入 stablePrefix）═══
    @ConfigField(
        label = "自动注入证据", fieldType = ConfigFieldType.TOGGLE,
        description = "开启后每回合自动从记忆/知识库按相关性注入 ≤3 条短证据（附来源）到上下文；" +
                "关闭则不自动注入（存量数据不删除，LLM 仍可调 memory_recall/kb_search 主动召回）。默认开启"
    )
    var evidenceAutoInject: Boolean = true

    override fun toJson(): String =
        """{"contextWindow":$contextWindow,"summaryMaxChars":$summaryMaxChars,""" +
            """"toolTrimEnabled":$toolTrimEnabled,"toolTrimChars":$toolTrimChars,""" +
            """"toolDiscoveryEnabled":$toolDiscoveryEnabled,"maxTools":$maxTools,""" +
            """"pinnedToolNames":${org.json.JSONObject.quote(pinnedToolNames)},"evidenceAutoInject":$evidenceAutoInject}"""

    override fun fromJson(json: String) {
        try {
            val obj = org.json.JSONObject(json)
            // 钳位兜底（§7.3——D51 恢复路径直读 JSON，须与 UI 同界）
            // N6.6：contextWindow 语义升级为世代切换线（旧持久值 256k 落界内，自动沿用）
            contextWindow = obj.optInt("contextWindow", 512_000).coerceIn(128_000, 960_000)
            summaryMaxChars = obj.optInt("summaryMaxChars", 600).coerceIn(200, 2000)
            toolTrimChars = obj.optInt("toolTrimChars", 500).coerceIn(200, 4000)
            toolTrimEnabled = obj.optBoolean("toolTrimEnabled", true)
            toolDiscoveryEnabled = obj.optBoolean("toolDiscoveryEnabled", true)
            maxTools = obj.optInt("maxTools", 8).coerceIn(4, 20)
            pinnedToolNames = obj.optString("pinnedToolNames", "").take(512)
            // R3：旧配置无此字段 → 默认 true（开启自动证据）
            evidenceAutoInject = obj.optBoolean("evidenceAutoInject", true)
        } catch (_: Exception) {}
    }

    override fun defaultConfig(): ModuleConfigBase = ContextBuilderConfig()
}

@Singleton
class ContextBuilderModule @Inject constructor(
    private val tokenBudgetAllocator: TokenBudgetAllocator
) : IZeroModule {

    override val metadata = ModuleMetadata(
        id = "context_builder",
        displayName = "上下文构建器",
        description = "组装 System Prompt 和本轮上下文",
        category = ModuleCategory.CORE,
        launchOrder = -80
    )

    private val _lifecycle = MutableStateFlow(ModuleLifecycle.UNINITIALIZED)
    override val lifecycle = _lifecycle.asStateFlow()

    override var isEnabled: Boolean = true
    override val requiredPermissions: List<String> = emptyList()

    override val config = ContextBuilderConfig()

    override suspend fun onAwake() { _lifecycle.value = ModuleLifecycle.AWAKE }
    override suspend fun onStart() { _lifecycle.value = ModuleLifecycle.STARTED }
    override suspend fun onDestroy() { _lifecycle.value = ModuleLifecycle.DESTROYED }
    override suspend fun onConfigChanged(newConfig: ModuleConfigBase) {}

    override suspend fun checkAvailability(): Boolean = true
    override fun markDisabled() { _lifecycle.value = ModuleLifecycle.DISABLED }

    // ═══════════════════════════════════════════════════════
    // System Prompt 构建
    // ═══════════════════════════════════════════════════════

    /**
     * 构建 PromptPlan（C1）：稳定前缀 + prefixVersion + 可变 turnContext。
     *
     * stablePrefix = persona + 协议 + 工具 manifest（字节稳定，连续轮相同输入相同字节），
     * 供 provider 前缀缓存命中。摘要/时间/情绪/证据等动态信息走 [turnContext]，不入 stablePrefix、
     * 不进 prefixVersion 哈希。prefixVersion = SHA-256(provider协议 | modelId | stableSystem | toolManifest)。
     *
     * 旧 [buildSystemPrompt] 把 summary 拼进 system 消息破坏缓存；C1 起 ChatBot 改用本方法，
     * summary 经 turnContext 注入历史之后、当前内容之前（见 ChatBotModule.buildMessagesWithTurnContext）。
     */
    fun buildPromptPlan(
        persona: String,
        history: List<ChatMessage>,
        turnContext: String?,
        tools: List<FunctionDefinition>,
        modelId: String,
        providerProtocol: String = PromptPrefix.PROVIDER_PROTOCOL_V1,
    ): PromptPlan {
        val sys = PromptPrefix.stableSystem(persona, providerProtocol)
        val manifest = PromptPrefix.toolManifest(tools)
        val stable = PromptPrefix.stablePrefix(persona, providerProtocol, tools)
        val version = PromptPrefix.prefixVersion(providerProtocol, modelId, sys, manifest)
        return PromptPlan(stable, version, history, turnContext, tools)
    }

}

