package com.zero.v2.core.data.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 自动化任务实体 — Zero v3.5（Step 3.5 Plan-Execute 引擎）。
 *
 * planJson 持久化 ExecutionPlan（goal + steps）。
 * 调度: nextRunAt 由 scheduleType/scheduleValue 推算，AutomationModule（AgentJob）按 tick 检查到期执行。
 */
@Entity(tableName = "automations")
data class AutomationEntity(
    @PrimaryKey val id: String,
    /** 短标题（展示用） */
    val title: String,
    /** 原始自然语言任务描述 */
    val description: String,
    /** ExecutionPlan 序列化 JSON */
    val planJson: String,
    /** ACTIVE / PAUSED / DELETED（软删除，保留历史） */
    val status: String = "ACTIVE",
    /** MANUAL（仅手动）/ DAILY（每日定时）/ INTERVAL（固定间隔） */
    val scheduleType: String = "MANUAL",
    /** v3.4 (D41): 执行模式 — PLAN（静态步骤）/ REACT（LLM 循环执行器） */
    val execMode: String = "PLAN",
    /** DAILY: "HH:mm"；INTERVAL: 秒数字符串；MANUAL: 空 */
    val scheduleValue: String = "",
    val lastRunAt: Long? = null,
    /** 下次执行时间（epoch ms）；Long.MAX_VALUE = 不自动执行 */
    val nextRunAt: Long = Long.MAX_VALUE,
    val runCount: Int = 0,
    val createdAt: Long = System.currentTimeMillis()
)
