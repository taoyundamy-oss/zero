package com.zero.v2.framework.chat

import com.zero.v2.core.common.ChatMessage
import com.zero.v2.core.common.MessageRole

/**
 * 世代切片器 — N6.6（设计 §九 9.7 世代压缩；取代 N6.1/N6.5 常驻滑窗）。
 *
 * 机制前提（用户裁定 2026-09-03）：压缩唯一使命=防 1M 上限报错；缓存命中是高优先级
 * （缓存价 ~1/10）→ **平时零干预（append-only 铁律）**，仅当发送视图超 90% 预算时
 * 由 ChatBotModule 触发一次"世代切换"：本切片器把视图切到 ~65% 预算（回落后需再涨
 * 25% 预算才会再切——天然稀疏），被置换头部异步压缩为摘要，此后新基线纯 append。
 *
 * 硬约束（§9.2 沿用）：
 *   ⑥ 配对边界——切片起点跳过 TOOL 消息（防无主 TOOL 400，X15）；
 *   TOOL 瘦身按**窗口前半段**位置判定（N6.5 定位化规则保留——切片为一次性事件，
 *   跨轮稳定性天然满足）。
 *
 * 纯函数：零状态、不改入参。
 */
object ContextWindow {

    data class Result(
        val messages: List<ChatMessage>,
        val keptCount: Int,
        val droppedCount: Int,
        /** 切片首条在入参列表中的下标（世代锚点=该消息 id） */
        val sliceStartIdx: Int,
        val estTokens: Int,
        val budget: Int,
    )

    /** Candidate requests use the same estimator as the trigger and final transport guard.
     * Only whole assistant/tool groups can be removed. The final group is always retained.
     * Bounded search assumes suffix costs decrease; every returned candidate is explicitly checked.
     */
    fun sliceByRequestBudget(
        messages: List<ChatMessage>, targetTokens: Int,
        estimate: (List<ChatMessage>) -> Int,
    ): Result {
        if (messages.isEmpty()) return Result(messages, 0, 0, 0, 0, targetTokens)
        val starts = messages.indices.filter { it == 0 || messages[it].role != MessageRole.TOOL }
        fun cost(index: Int) = estimate(messages.subList(starts[index], messages.size))
        var chosen = starts.lastIndex
        var chosenCost = cost(chosen)
        var low = 0
        var high = chosen - 1
        while (low <= high) {
            val mid = (low + high) ushr 1
            val tokens = cost(mid)
            if (tokens <= targetTokens) {
                chosen = mid
                chosenCost = tokens
                high = mid - 1
            } else low = mid + 1
        }
        val start = starts[chosen]
        return Result(messages.subList(start, messages.size).toList(), messages.size - start,
            start, start, chosenCost, targetTokens)
    }

    /**
     * 从尾部保留 targetTokens 预算内的消息（末条必保留），前置跳过无主 TOOL。
     */
    fun sliceForGeneration(
        messages: List<ChatMessage>,
        targetTokens: Int,
        estimator: (ChatMessage) -> Int,
        toolTrimEnabled: Boolean,
        toolTrimChars: Int,
    ): Result {
        if (messages.isEmpty()) {
            return Result(messages, 0, 0, 0, 0, targetTokens)
        }

        var start = messages.size - 1
        var acc = estimator(messages.last())
        while (start > 0) {
            val t = estimator(messages[start - 1])
            if (acc + t > targetTokens) break
            acc += t
            start--
        }
        // §9.2-⑥ 配对边界：起点跳过无主 TOOL
        // A tail of tool results must keep its assistant call, even when that atomic group exceeds the target.
        // Moving forward used to leave the final TOOL orphaned when the whole suffix was TOOL.
        while (start > 0 && messages[start].role == MessageRole.TOOL) start--

        // TOOL 瘦身：保留窗口的前半段（位置判定）
        val trimBeforeIdx: Int = if (toolTrimEnabled) {
            start + (messages.size - start) / 2
        } else {
            start
        }

        val kept = messages.subList(start, messages.size).mapIndexed { i, m ->
            val abs = start + i
            if (m.role == MessageRole.TOOL && abs < trimBeforeIdx &&
                (m.content?.length ?: 0) > toolTrimChars) {
                m.copy(content = m.content!!.take(toolTrimChars) + "\n…[已瘦身，可重新调用工具获取完整结果]")
            } else m
        }

        return Result(
            messages = kept,
            keptCount = kept.size,
            droppedCount = start,
            sliceStartIdx = start,
            estTokens = kept.sumOf(estimator),
            budget = targetTokens,
        )
    }
}
