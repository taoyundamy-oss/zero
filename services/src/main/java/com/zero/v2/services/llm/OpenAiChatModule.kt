package com.zero.v2.services.llm

import com.zero.v2.core.moduleapi.ModuleCategory
import com.zero.v2.core.moduleapi.ModuleLifecycle
import com.zero.v2.core.moduleapi.ModuleMetadata
import com.zero.v2.core.moduleapi.IZeroModule
import com.zero.v2.core.moduleapi.ModuleConfigBase
import com.zero.v2.core.trace.AgentTraceManager
import com.zero.v2.core.trace.NodeType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * LLM 统一接入模块 — 开发前规范化 N2.6a（收敛骨架）。
 *
 * 按 LlmProfileStore 的启用 CHAT profile 动态注册 ProfileLanguageModel 进 ModelSelector
 * ——新增/切换模型零代码（D59 同族：接入=统一 OpenAI 兼容层，差异=配置与适配器）。
 *
 * N2.6a 双轨过渡：deepseek/qwen 仍由 legacy DeepSeekChatModule/QwenChatModule 自注册
 * （避免 ModelSelector 重复注册），本模块暂跳过；N2.6b 切换（解绑 legacy）后移除 skip。
 */
@Singleton
class OpenAiChatModule @Inject constructor(
    private val client: OpenAiCompatibleClient,
    private val profileStore: LlmProfileStore,
    private val traceManager: AgentTraceManager,
    modelSelector: ModelSelector,
    private val chatGptAuth: com.zero.v2.services.chatgpt.ChatGptAuth,
) : IZeroModule {

    companion object {
        private const val TRACE_ID = "openai_chat"
    }

    private val selector = modelSelector

    override val metadata = ModuleMetadata(
        id = "openai_chat",
        displayName = "LLM 统一接入",
        description = "OpenAI 兼容统一接入：按 Profile 注册模型（新增/切换模型零代码）",
        category = ModuleCategory.CORE,
        launchOrder = -175
    )

    private val _lifecycle = MutableStateFlow(ModuleLifecycle.UNINITIALIZED)
    override val lifecycle = _lifecycle.asStateFlow()
    override var isEnabled: Boolean = true
    override val requiredPermissions: List<String> = emptyList()
    override val config: ModuleConfigBase = object : ModuleConfigBase() {
        override fun toJson() = "{}"
        override fun fromJson(json: String) {}
        override fun defaultConfig() = this
    }

    override suspend fun onAwake() {
        _lifecycle.value = ModuleLifecycle.AWAKE
        chatGptAuth.reload()
        registerProfiles()
    }
    override suspend fun onStart() { _lifecycle.value = ModuleLifecycle.STARTED }
    override suspend fun onDestroy() { _lifecycle.value = ModuleLifecycle.DESTROYED }
    override suspend fun onConfigChanged(newConfig: ModuleConfigBase) {}

    override suspend fun checkAvailability(): Boolean = true
    override fun markDisabled() { _lifecycle.value = ModuleLifecycle.DISABLED }

    /** 本模块已注册的 profileId（refresh 原子替换，防停用/改模型后残留） */
    private val registeredProfileIds = mutableSetOf<String>()

    /** 注册启用中的 CHAT profile */
    private fun registerProfiles() = refreshProfiles()

    /** 重同步注册（N2.6c：LlmProfilesScreen 启停/编辑后调用，切换即时生效） */
    fun refreshProfiles() {
        synchronized(registeredProfileIds) {
            val profiles = profileStore.getEnabled(LlmProfile.Kind.CHAT)
            val replacements = profiles.map { p ->
                if (ChatGptPlanProtocol.enabled(p)) ChatGptPlanLanguageModel(p, client, chatGptAuth, profileStore)
                else ProfileLanguageModel(profileId = p.id, fallback = p, client = client,
                    profileStore = profileStore)
            }
            selector.replaceProfiles(registeredProfileIds, replacements)
            registeredProfileIds.clear()
            registeredProfileIds += profiles.map { it.id }
            val registered = profiles.map { "${it.id}:${it.modelId}" }
            traceManager.record(TRACE_ID, NodeType.SYSTEM_EVENT, metadata.id,
                "profiles_registered: n=${registered.size} ${registered.joinToString(",")}")
        }
    }

}
