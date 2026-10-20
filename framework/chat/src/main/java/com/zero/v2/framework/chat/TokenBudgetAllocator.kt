package com.zero.v2.framework.chat

import javax.inject.Inject
import javax.inject.Singleton

/**
 * Token 预算器 — persona/工具表截断配额 + Token 估算（N6.7 清账版 / C2 全预算模型）。
 *
 * C2（设计§4.3）：预算按模型真实 contextWindow − 输出预留 − 安全余量计算；稳定 system/核心工具
 * 先占位，再给最近历史、证据和动态风格设硬上限。推荐默认：证据 ≤8% available、动态风格 ≤120、
 * 摘要 ≤800、工具定义总额 ≤15%。旧 [allocate]（仅 persona+tools）保留迁移兼容。
 */
@Singleton
class TokenBudgetAllocator @Inject constructor() {

    /** C2 全预算：模型上下文 − 输出预留 − 安全余量 → 各段硬上限。 */
    data class Budget(
        val modelContextWindow: Int,
        val outputReserve: Int,
        val safetyMargin: Int,
        val availableInput: Int,        // = contextWindow − outputReserve − safetyMargin
        val systemAndPersona: Int,      // 稳定 system（15% available）
        val toolsDefinition: Int,       // 工具定义总额（≤15% available）
        val history: Int,               // 最近历史（剩余，≥0）
        val turnContext: Int,           // 本轮短上下文（证据+风格+摘要）
        val evidenceMax: Int,           // 证据（≤8% available）
        val styleMax: Int,              // 动态风格（≤120）
        val summaryMax: Int,            // 摘要（≤800）
    ) {
        /** 各段总和不超过 availableInput（防超模型预算）。 */
        val totalAllocated: Int get() = systemAndPersona + toolsDefinition + history + turnContext
    }

    /** C2 全预算分配。outputReserve 默认 4096；safetyMargin 默认 5%。 */
    fun allocateFull(
        modelContextWindow: Int,
        outputReserve: Int = 4096,
        safetyMarginRatio: Double = 0.05,
    ): Budget {
        val safety = (modelContextWindow * safetyMarginRatio.coerceIn(0.0, 0.2)).toInt()
        val available = (modelContextWindow - outputReserve - safety).coerceAtLeast(0)
        val system = (available * 0.15).toInt()
        val tools = (available * 0.15).toInt()
        val evidence = (available * 0.08).toInt()
        val remaining = (available - system - tools - evidence).coerceAtLeast(0)
        val style = minOf(120, remaining)
        val summary = minOf(800, remaining - style)
        val turnCtx = evidence + style + summary
        val history = (available - system - tools - turnCtx).coerceAtLeast(0)
        return Budget(
            modelContextWindow = modelContextWindow,
            outputReserve = outputReserve,
            safetyMargin = safety,
            availableInput = available,
            systemAndPersona = system,
            toolsDefinition = tools,
            history = history,
            turnContext = turnCtx,
            evidenceMax = evidence,
            styleMax = style,
            summaryMax = summary,
        )
    }

    /** 旧 allocate（迁移兼容）：仅 persona+tools 截断配额。 */
    fun allocate(contextWindow: Int = 512_000): Budget = allocateFull(contextWindow)

    /** Keep append-only generations, but the selected model is always the hard ceiling. */
    fun historyInputBudget(
        generationLimit: Int,
        modelContextWindow: Int,
        outputReserve: Int,
        stablePrefix: String,
        toolManifest: String,
    ): Int {
        val input = allocateFull(modelContextWindow, outputReserve).availableInput
        // Framing plus the final tools-free synthesis directive also needs room.
        val overhead = estimateTokens(stablePrefix) + estimateTokens(toolManifest) + REQUEST_FRAMING_RESERVE
        return minOf(generationLimit, (input - overhead).coerceAtLeast(0))
    }

    private companion object {
        const val REQUEST_FRAMING_RESERVE = 256
    }

    /**
     * 估算文本的 Token 数。
     * 简易估算：中文 ~1.5 字符/token，英文 ~4 字符/token。
     */
    fun estimateTokens(text: String): Int {
        var tokens = 0L
        for (ch in text) {
            tokens += if (ch.code > 127) 2 else 1  // 非 ASCII 加权
        }
        return (tokens / 2).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()  // Conservative fallback, not a tokenizer
    }

    /**
     * 估算消息列表的 Token 数。
     * N6.1（X14）：补计 toolCalls（name+arguments）与每消息固定开销（role/结构 ~4 token）。
     */
    fun estimateMessageTokens(messages: List<com.zero.v2.core.common.ChatMessage>): Int {
        return messages.sumOf { msg ->
            val visible = estimateTokens(msg.content ?: "").toLong() + estimateTokens(msg.thinking ?: "") +
                msg.toolCalls.sumOf { estimateTokens(it.name).toLong() + estimateTokens(it.arguments) }
            // Opaque reasoning is transmitted too. This is a conservative wire-size estimate,
            // not a provider tokenizer; avoid counting the duplicate visible text/tools twice.
            maxOf(visible, msg.providerContent?.let { estimateTokens(it.contentJson).toLong() } ?: 0L) + 4
        }.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    }

    /** 单条消息估算（ContextWindow 逐条累加用，与列表口径一致） */
    fun estimateMessageTokens(message: com.zero.v2.core.common.ChatMessage): Int =
        estimateMessageTokens(listOf(message))
}
