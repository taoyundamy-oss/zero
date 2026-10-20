package com.zero.v2.services.module

import android.app.AppOpsManager
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Build
import android.os.Process
import com.zero.v2.core.common.ActionPermission
import com.zero.v2.core.common.FunctionDefinition
import com.zero.v2.core.moduleapi.*
import com.zero.v2.core.common.FunctionResult
import com.zero.v2.core.trace.AgentTraceManager
import com.zero.v2.core.trace.NodeType
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 使用量统计模块 — Zero v2.2 → v2.5 真实实现。
 *
 * Android 独有能力：通过 UsageStatsManager 获取设备使用时长和应用排行。
 * 让 AI 能够主动关心用户的使用习惯和健康。
 *
 * v2.5: get_screen_time / get_app_usage 从 stub 改为真实 UsageStatsManager 查询。
 *       PACKAGE_USAGE_STATS 为特殊权限（AppOps），未授予时返回可操作错误提示。
 *       权限迁移（DESIGN §5.1）: get_screen_time / get_app_usage = AUTO（只读）。
 */
@Singleton
class UsageStatsModule @Inject constructor(
    @ApplicationContext private val context: Context,

    private val traceManager: AgentTraceManager
) : IZeroModule, com.zero.v2.core.moduleapi.ToolContributor, ITimedModule {

    override val metadata = ModuleMetadata(
        id = "usage_stats",
        displayName = "使用统计",
        description = "获取设备使用时长和应用使用排行。了解用户的使用习惯。",
        category = ModuleCategory.SYSTEM,
        launchOrder = 120
    )

    private val _lifecycle = MutableStateFlow(ModuleLifecycle.UNINITIALIZED)
    override val lifecycle = _lifecycle.asStateFlow()
    override var isEnabled: Boolean = true
    override val requiredPermissions: List<String> = listOf(
        android.Manifest.permission.PACKAGE_USAGE_STATS
    )
    override val config = object : ModuleConfigBase() {
        override fun toJson() = "{}"
        override fun fromJson(json: String) {}
        override fun defaultConfig() = this
    }

    override suspend fun onAwake() { _lifecycle.value = ModuleLifecycle.AWAKE }
    override suspend fun onStart() { _lifecycle.value = ModuleLifecycle.STARTED }
    override suspend fun onDestroy() { _lifecycle.value = ModuleLifecycle.DESTROYED }
    override suspend fun onConfigChanged(newConfig: ModuleConfigBase) {}
    override suspend fun checkAvailability(): Boolean = true
    override fun markDisabled() { _lifecycle.value = ModuleLifecycle.DISABLED }

    override suspend fun onTick(tickMs: Long) {
        // 每 15 分钟收集一次使用量数据（Phase 9 简化实现）
    }

    override fun contributions(): List<com.zero.v2.core.moduleapi.ToolContribution> =
        com.zero.v2.core.moduleapi.toolDeclarations(metadata.id) {
        val functionRegistry = this
        functionRegistry.register(
            FunctionDefinition("get_screen_time", "获取设备使用统计。包括总使用时长和各应用排行。",
                mapOf("type" to "object", "properties" to mapOf(
                    "period" to mapOf("type" to "string", "description" to "统计周期: today/week/month")
                ))
            ), metadata.id, permission = ActionPermission.AUTO
        ) { args -> screenTime(args["period"] as? String ?: "today") }

        functionRegistry.register(
            FunctionDefinition("get_app_usage", "查询指定应用的使用详情。",
                mapOf("type" to "object", "properties" to mapOf(
                    "package_name" to mapOf("type" to "string", "description" to "应用包名")
                ), "required" to listOf("package_name"))
            ), metadata.id, permission = ActionPermission.AUTO
        ) { args -> appUsage(args["package_name"] as? String ?: "") }
    }

    // ═══════════════════════════════════════════════════════
    // v2.5: 真实 UsageStatsManager 查询
    // ═══════════════════════════════════════════════════════

    /** PACKAGE_USAGE_STATS 特殊权限检查（AppOps，需用户在系统设置中授予） */
    private fun hasUsageAccess(): Boolean = try {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = if (Build.VERSION.SDK_INT >= 29) {
            appOps.unsafeCheckOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
        } else {
            @Suppress("DEPRECATION")
            appOps.checkOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
        }
        mode == AppOpsManager.MODE_ALLOWED
    } catch (_: Exception) {
        false
    }

    private fun screenTime(period: String): FunctionResult {
        if (!hasUsageAccess()) {
            traceManager.record("fc_get_screen_time", NodeType.ERROR, metadata.id,
                "screen_time_blocked: PACKAGE_USAGE_STATS 未授予")
            return FunctionResult.failure(
                "使用情况访问权限未授予。请引导用户到 系统设置 → 隐私 → 使用情况访问 中允许 ZeroV2 后重试。")
        }

        val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val now = System.currentTimeMillis()
        val cal = Calendar.getInstance()
        val (start, interval, periodLabel) = when (period.lowercase()) {
            "week" -> Triple(startOfDay(cal.apply { add(Calendar.DAY_OF_YEAR, -6) }).timeInMillis,
                UsageStatsManager.INTERVAL_WEEKLY, "近7天")
            "month" -> Triple(startOfDay(cal.apply { set(Calendar.DAY_OF_MONTH, 1) }).timeInMillis,
                UsageStatsManager.INTERVAL_MONTHLY, "本月")
            else -> Triple(startOfDay(cal).timeInMillis,
                UsageStatsManager.INTERVAL_DAILY, "今日")
        }

        val stats = usm.queryUsageStats(interval, start, now).orEmpty()
            .filter { it.totalTimeInForeground > 0 }
            .sortedByDescending { it.totalTimeInForeground }

        if (stats.isEmpty()) {
            traceManager.record("fc_get_screen_time", NodeType.TOOL_CALL_RESULT, metadata.id,
                "screen_time_queried: period=$period empty=true")
            return FunctionResult.success("$periodLabel 暂无前台使用记录。")
        }

        val totalMs = stats.sumOf { it.totalTimeInForeground }
        val pm = context.packageManager
        val topApps = stats.take(8).joinToString("\n") { s ->
            val appName = try {
                pm.getApplicationLabel(pm.getApplicationInfo(s.packageName, 0)).toString()
            } catch (_: Exception) {
                s.packageName
            }
            "- $appName: ${formatDuration(s.totalTimeInForeground)}"
        }

        traceManager.record("fc_get_screen_time", NodeType.TOOL_CALL_RESULT, metadata.id,
            "screen_time_queried: period=$period totalMin=${totalMs / 60_000} apps=${stats.size}")

        return FunctionResult.success(buildString {
            appendLine("=== 使用统计 ($periodLabel) ===")
            appendLine("总使用时长: ${formatDuration(totalMs)}")
            appendLine("有前台使用的应用数: ${stats.size}")
            appendLine()
            appendLine("Top 应用:")
            appendLine(topApps)
            appendLine()
            appendLine("请基于以上数据回答用户问题。")
        })
    }

    private fun appUsage(packageName: String): FunctionResult {
        if (packageName.isBlank()) return FunctionResult.failure("package_name 不能为空")
        if (!hasUsageAccess()) {
            traceManager.record("fc_get_app_usage", NodeType.ERROR, metadata.id,
                "app_usage_blocked: PACKAGE_USAGE_STATS 未授予 packageName=$packageName")
            return FunctionResult.failure(
                "使用情况访问权限未授予。请引导用户到 系统设置 → 隐私 → 使用情况访问 中允许 ZeroV2 后重试。")
        }

        val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val start = startOfDay(Calendar.getInstance()).timeInMillis
        val stat = usm.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, start, System.currentTimeMillis())
            .orEmpty()
            .firstOrNull { it.packageName == packageName }

        traceManager.record("fc_get_app_usage", NodeType.TOOL_CALL_RESULT, metadata.id,
            "app_usage_queried: package=$packageName found=${stat != null}")

        return if (stat != null && stat.totalTimeInForeground > 0) {
            val timeFmt = SimpleDateFormat("HH:mm", Locale.getDefault())
            FunctionResult.success(
                "$packageName 今日前台使用: ${formatDuration(stat.totalTimeInForeground)}，" +
                        "上次使用: ${timeFmt.format(Date(stat.lastTimeUsed))}")
        } else {
            FunctionResult.success("$packageName 今日无前台使用记录。")
        }
    }

    /** 将日历归零到当日 00:00 */
    private fun startOfDay(c: Calendar): Calendar = c.apply {
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }

    private fun formatDuration(ms: Long): String {
        val hours = ms / 3_600_000
        val minutes = (ms % 3_600_000) / 60_000
        return if (hours > 0) "${hours}小时${minutes}分钟" else "${minutes}分钟"
    }
}
