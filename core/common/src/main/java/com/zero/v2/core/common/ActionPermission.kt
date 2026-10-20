package com.zero.v2.core.common

/**
 * 统一的行动权限（DESIGN §4.2 / §5.1，D9/D24）。
 *
 * 替代 v2.2 的 SAFE/SENSITIVE（FunctionSecurity）双轨：一个 FC 函数只声明一次权限，
 * 不论调用来源（LLM FC / Agent 自动化 / 用户手动触发）走同一套检查。
 *
 * Phase 0-2 简化（D24）：仅启用 AUTO/CONFIRM 两级检查；
 * FORBIDDEN 保留枚举定义，Phase 3+ 自动化激活后再启用硬拦截。
 */
enum class ActionPermission {
    /** 始终自动放行，不弹窗（只读操作：web_search / vision_analyze / memory_recall） */
    AUTO,

    /** 需要用户确认（写入/推送操作：memory_write / push_notification） */
    CONFIRM,

    /** 禁止自主执行，仅用户手动触发（Phase 3+ 自动化激活后启用） */
    FORBIDDEN
}
