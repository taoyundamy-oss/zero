package com.zero.v2.services.agent

/**
 * Agent 自身状态（DESIGN D22）。
 *
 * 独立于 Automation 的任务状态机 — 这是 Agent "生命体" 的宏观状态。
 * Phase 3.1: IDLE/OBSERVING 由 AgentRuntime tick 循环驱动；
 * 其余状态由后续 Phase 接入（EXECUTING ← Plan-Execute、REFLECTING ← Memory Reflection、
 * WAITING ← 用户确认弹窗）。
 */
enum class AgentState {
    /** 空闲 — 无自主行为，等待下一个 tick */
    IDLE,

    /** 观察中 — tick 已触发，环境信息收集/分发给订阅模块 */
    OBSERVING,

    /** 执行中 — 正在执行自主行动（Phase 3.5+ Automation Plan-Execute） */
    EXECUTING,

    /** 反思中 — 记忆整理/冲突检测/洞察生成（Phase 4+ Memory Evolution） */
    REFLECTING,

    /** 等待中 — 等待用户确认或外部条件满足（Phase 8+ 确认弹窗） */
    WAITING
}
