package com.zero.v2.services.llm

import com.zero.v2.core.common.ChatMessage
import com.zero.v2.core.common.FunctionDefinition
import com.zero.v2.core.common.StreamChunk
import kotlinx.coroutines.flow.Flow

/**
 * 语言模型接口 — Zero v2 Phase 1。
 *
 * 统一的 LLM 调用抽象，解耦 ChatBotModule 与具体模型实现。
 * 每个模型提供商（DeepSeek / Qwen / Custom）独立实现此接口。
 *
 * 与旧 ModelAdapter 的区别：
 *   - ModelAdapter 关注 HTTP 层面（apiKey、temperature、maxTokens 入参）
 *   - ILanguageModel 关注能力层面（无 apiKey 入参，由实现内部管理）
 *   - ILanguageModel 增加 capabilities 声明，供 ModelSelector 能力匹配
 */
interface ILanguageModel {
    /** 模型唯一标识，如 "deepseek-chat" */
    val modelId: String

    /** Stable configuration identity; wire modelId can be shared by several connections. */
    val profileId: String get() = modelId

    /** 模型显示名称，如 "DeepSeek V4 Flash" */
    val displayName: String

    /** 上下文窗口大小（tokens） */
    val contextWindow: Int

    /** Reserve the actual configured output before retaining conversation history. */
    val outputReserve: Int get() = DEFAULT_OUTPUT_RESERVE

    companion object {
        const val DEFAULT_OUTPUT_RESERVE = 4096
    }

    /** 该模型支持的能力集合（M2：来自 profile 显式声明，不推断 modelId） */
    val capabilities: Set<ModelCapability>

    /** M2：API Key 是否已配置（缺 Key 不入候选）。 */
    val keyConfigured: Boolean

    /** M2：用户优先级（高=首选；tie-break）。 */
    val priority: Int

    /** M2：是否启用（profile.enabled；禁用不入候选）。 */
    val enabled: Boolean

    /** Identity for bounded retrieval decisions, excluding credentials. */
    val retrievalIdentity: String get() = modelId

    /** Freeze routing, parameters and credentials for one complete Agent tool loop. */
    fun snapshotForTurn(): ILanguageModel = this

    /** Optional request-wide estimate backed by exact-prefix provider observations. No network call. */
    fun estimateInputTokens(messages: List<ChatMessage>, systemPrompt: String?,
        functions: List<FunctionDefinition>): Int? = null

    fun inputBudgetDiagnostic(messages: List<ChatMessage>, systemPrompt: String?,
        functions: List<FunctionDefinition>): String = "source=local_fallback"

    fun noteContextEvent(messages: List<ChatMessage>, event: String) = Unit

    /** Optional bounded, tools-free completion; unsupported clients fall back without a request. */
    suspend fun boundedCompletion(userMessage: String, systemPrompt: String): String? = null

    /**
     * 流式对话调用。
     *
     * @param messages 对话历史（不含 system prompt）
     * @param systemPrompt 系统提示词（C1 起为 stablePrefix，字节稳定）
     * @param functions 可用的 Function Calling 定义
     * @param prefixVersion C1：稳定前缀哈希 SHA-256(provider协议|modelId|stableSystem|toolManifest)，
     *   供 trace 观测前缀一致性（不记原 prompt/Key）；null=未计算（旧路径）
     * @return 流式响应块
     */
    fun chatStream(
        messages: List<ChatMessage>,
        systemPrompt: String?,
        functions: List<FunctionDefinition>,
        prefixVersion: String? = null,
    ): Flow<StreamChunk>

    /**
     * 同步对话调用（非流式，用于简单查询）。
     *
     * @param userMessage 用户消息
     * @param systemPrompt 系统提示词
     * @return 完整响应文本
     */
    suspend fun chatSync(
        userMessage: String,
        systemPrompt: String? = null
    ): String
}
