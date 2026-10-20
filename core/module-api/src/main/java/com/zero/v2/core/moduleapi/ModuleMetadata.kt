package com.zero.v2.core.moduleapi

/**
 * 模块元数据。
 *
 * 对标 Alife 的 [Module("displayName", "description", ...)] 属性。
 * 每个模块在构造时提供此元数据，供 ModuleLifecycleManager 进行
 * 注册、排序、分类展示和崩溃隔离。
 */
data class ModuleMetadata(
    /** 模块唯一标识符（如 "web_search"、"system_event"） */
    val id: String,
    /** 模块显示名称（如 "网页搜索"） */
    val displayName: String,
    /** 模块功能描述（供 LLM Function Calling 使用，描述何时使用此模块） */
    val description: String,
    /** 模块分类 */
    val category: ModuleCategory,
    /**
     * 启动顺序。数值越小越先启动。
     * 对标 Alife 的 LaunchOrder。
     * 典型值：
     *   -200: ChatBotModule (最先)
     *   -100: MessageFilterModule
     *    0:   默认
     *    100:  SystemEventModule
     *    200:  BackgroundServiceModule (最后)
     */
    val launchOrder: Int = 0,
    /** 模块版本号 */
    val version: String = "1.0.0",
    /** 模块作者 */
    val author: String = "Zero",
    /** 标签列表（用于 UI 筛选） */
    val tags: List<String> = emptyList(),
    /** 是否需要前台 Service 才能正常工作 */
    val requiresForegroundService: Boolean = false
)
