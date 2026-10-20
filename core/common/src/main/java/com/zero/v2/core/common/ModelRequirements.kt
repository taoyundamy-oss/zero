package com.zero.v2.core.common

/**
 * 本轮输入模态（迭代开发设计§2.1）——M2 ModelSelector 按 [ModelRequirements] 路由。
 */
enum class InputModality { TEXT, IMAGE, AUDIO, FILE, VIDEO }

/**
 * 模型需求（迭代开发设计§2.1 / 落地计划 M2）。
 *
 * 由本轮真实输入计算：文本→TEXT；图片→IMAGE；音频若 profile 支持原生音频直送，否则 ASR→文本。
 * [needsTools]=工具调用；[needsStreaming]=流式；[minContextTokens]=最小上下文预算。
 * ModelSelector 据此筛候选 + 按用户优先级/可用性/确定性 tie-break，不把 CHAT 等同 IMAGE_INPUT。
 * 模态→ModelCapability 映射在 services/llm ModelSelector（可访问 ModelCapability 枚举）。
 */
data class ModelRequirements(
    val input: Set<InputModality>,
    val needsTools: Boolean = false,
    val needsStreaming: Boolean = true,
    val minContextTokens: Int = 0,
)
