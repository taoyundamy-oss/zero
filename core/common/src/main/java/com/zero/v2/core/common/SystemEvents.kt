package com.zero.v2.core.common

/**
 * 系统总线事件类型定义。
 *
 * 对标 Alife 的系统级事件 + v2.1 新增 OnScheduleTriggered 事件解耦。
 */

/** 触发来源 */
enum class TriggerSource { HEARTBEAT, ALARM, MANUAL }

/** 调度触发事件（v2.1：解耦 SystemEventModule → AutomationModule） */
data class ScheduleTriggeredEvent(
    val automationId: String,
    val triggeredAt: Long,
    val source: TriggerSource
)

/** 系统通知到达事件 */
data class NotificationEvent(
    val packageName: String,
    val appName: String,
    val title: String?,
    val content: String?,
    val timestamp: Long
)

/** 电池状态事件 */
data class BatteryEvent(
    val level: Int,
    val isCharging: Boolean,
    val isLow: Boolean
)

/** 安全确认请求事件（v2.2：SENSITIVE 操作弹窗确认） */
data class SecurityConfirmRequest(
    val requestId: String,
    val functionName: String,
    val description: String,
    val params: Map<String, Any?>
)

/** 安全确认响应 */
data class SecurityConfirmResponse(
    val requestId: String,
    val approved: Boolean
)
