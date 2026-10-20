package com.zero.v2.services.agent

/**
 * Agent 定时任务接口（DESIGN §2.4 / D21）。
 *
 * 模块不再直接订阅 systemBus.onTick 做 `if (shouldRun())` 轮询 —
 * 改为实现 AgentJob，声明自己的下次执行时间，由 Scheduler 统一分发。
 *
 * 契约:
 *   - [nextRunTime]: 下次执行时间（epoch ms）。`Long.MAX_VALUE` = 暂停（无待办）
 *   - [execute]: 执行任务。执行后模块自行推进 nextRunTime（内部状态）；
 *     未推进则下一 tick 会立即再次执行 — 实现方负责推进与幂等。
 *
 * 为多 Agent 任务分解预留（Phase 7）：id 即任务身份，execute 即任务体。
 */
interface AgentJob {
    val id: String
    val nextRunTime: Long
    suspend fun execute()
}
