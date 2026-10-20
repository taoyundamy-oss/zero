package com.zero.v2.core.common

import kotlinx.coroutines.flow.Flow

/**
 * 模型适配器接口 — 统一 LLM 调用协议。Zero v2.3。
 *
 * 每个模型提供商实现此接口。
 *
 * ⚠️ 取消契约：chatStream 实现方必须在 Flow 的 awaitClose 中调用 OkHttp Call.cancel()。
 * UI 取消订阅时必须立即断开底层 HTTP 连接，防止后台持续消耗 Token。
 */
interface ModelAdapter {
    val modelId: String
    val displayName: String
    val provider: ModelProvider
    val contextWindow: Int
    val supportsFC: Boolean
    val supportsThinking: Boolean

    suspend fun chatSync(
        userMessage: String, systemPrompt: String?,
        apiKey: String, temperature: Float, maxTokens: Int
    ): String

    fun chatStream(
        messages: List<ChatMessage>, systemPrompt: String?,
        functions: List<FunctionDefinition>,
        apiKey: String, temperature: Float, maxTokens: Int,
        enableThinking: Boolean = true, reasoningEffort: String = "high",
        thinkingBudget: Int = 1024
    ): Flow<StreamChunk>
}

enum class ModelProvider { DEEPSEEK, QWEN, OPENAI, CUSTOM }
