package com.zero.v2.app.ui.navigation

/** Each root entry has one home; contextual links must reuse the same destination and settings owner. */
data class AppDestination(val route: String, val title: String, val description: String, val section: String)

object AppDestinations {
    fun rootFor(route: String?): String = when {
        route == "live_session" || route == "chat" -> "chat"
        route == "library" || library.any { it.route == route } -> "library"
        route == "automation" || route == "automation_create" || route?.startsWith("dyn_module/") == true ||
            workspace.any { it.route == route } -> "automation"
        else -> "settings"
    }
    val library = listOf(
        AppDestination("work_files", "知识与工作文件", "浏览文件夹、阅读 Markdown 与管理知识文档", "资料"),
        AppDestination("memory_manage", "长期记忆", "查找、核对和更正已保存的记忆", "资料"),
        AppDestination("memory_graph", "记忆图谱", "查看人物、事物与它们之间的关系", "资料"),
        AppDestination("music_library", "音乐库", "浏览与管理工作目录中的音乐", "资料"),
    )
    val workspace = listOf(
        AppDestination("automation_list", "自动化任务", "创建任务、查看进度和执行结果", "工作区"),
        AppDestination("dynamic_modules", "动态模块", "启停模块、查看配置与部署版本", "工作区"),
        AppDestination("logs", "运行记录", "查看思考、工具调用与错误详情", "工作区"),
    )
    val settings = listOf(
        AppDestination("character_manage", "角色与表达", "管理角色、表达风格与角色状态", "偏好"),
        AppDestination("appearance", "外观", "明暗模式与界面配色", "偏好"),
        AppDestination("agent_behavior", "自主行为", "在场退避、离场节奏与记忆整理", "行为与能力"),
        AppDestination("trigger_settings", "事件感知", "选择可感知的应用、通知与提醒", "行为与能力"),
        AppDestination("modules", "能力与模型", "模块开关、模型连接与专属参数", "行为与能力"),
        AppDestination("mcp_servers", "外部工具连接", "管理 MCP 服务连接", "行为与能力"),
        AppDestination("permission_center", "系统权限", "核对授权并前往系统设置", "系统与数据"),
        AppDestination("assistant_role", "系统语音助手", "通过系统手势或电源键唤起 Zero", "系统与数据"),
        AppDestination("usage", "成本与用量", "用量趋势、缓存命中与预算保护", "系统与数据"),
        AppDestination("vector_maintenance", "检索索引维护", "查看记忆与知识库索引、确认回滚", "系统与数据"),
        AppDestination("data_backup", "备份与恢复", "导入或导出对话、角色、记忆和任务", "系统与数据"),
    )
}
