package com.zero.v2.core.moduleapi

/**
 * 模块分类枚举。
 *
 * 对标 Alife 的 defaultCategory 参数。
 * 用于模块管理中分组展示、启动顺序编排和崩溃隔离策略。
 */
enum class ModuleCategory(val label: String) {
    /** 核心模块 — 始终加载，不可禁用，崩溃后不自动隔离 */
    CORE("核心"),
    /** AI 工具模块 — LLM 可通过 Function Calling 调用 */
    INSTRUMENT("AI工具"),
    /** 系统集成模块 — Android 平台能力封装 */
    SYSTEM("系统集成"),
    /** 自动化模块 — 定时/条件触发的任务执行 */
    AUTOMATION("自动化")
}
