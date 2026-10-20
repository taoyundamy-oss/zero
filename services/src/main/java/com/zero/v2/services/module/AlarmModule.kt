package com.zero.v2.services.module

import android.content.Context
import android.content.Intent
import android.provider.AlarmClock
import com.zero.v2.core.common.ActionPermission
import com.zero.v2.core.common.FunctionDefinition
import com.zero.v2.core.moduleapi.IZeroModule
import com.zero.v2.core.moduleapi.ModuleCategory
import com.zero.v2.core.moduleapi.ModuleConfigBase
import com.zero.v2.core.moduleapi.ModuleLifecycle
import com.zero.v2.core.moduleapi.ModuleMetadata
import com.zero.v2.core.common.FunctionResult
import com.zero.v2.core.trace.AgentTraceManager
import com.zero.v2.core.trace.NodeType
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 系统闹钟模块 — Step 9.4（D58 系统级感知）。
 *
 * AlarmClock.ACTION_SET_ALARM 标准 Intent API（兼容三星/Google/小米闹钟应用）。
 * FC set_alarm(hour, minute, message)（CONFIRM）。
 * FC set_timer(seconds, message)（CONFIRM）。
 *
 * 注意：EXTRA_SKIP_UI=true 时部分 OEM 闹钟应用仍会弹出确认界面（行为正常）。
 */
@Singleton
class AlarmModule @Inject constructor(
    @ApplicationContext private val context: Context,

    private val traceManager: AgentTraceManager
) : IZeroModule, com.zero.v2.core.moduleapi.ToolContributor {

    companion object {
        private const val TRACE_ID = "alarm"
    }

    override val metadata = ModuleMetadata(
        id = "alarm",
        displayName = "系统闹钟",
        description = "设置系统闹钟和计时器（标准 AlarmClock API，兼容三星/Google/小米）",
        category = ModuleCategory.SYSTEM,
        launchOrder = 125
    )

    private val _lifecycle = MutableStateFlow(ModuleLifecycle.UNINITIALIZED)
    override val lifecycle = _lifecycle.asStateFlow()
    override var isEnabled: Boolean = true
    override val requiredPermissions: List<String> = listOf(
        "com.android.alarm.permission.SET_ALARM"
    )
    override val config = object : ModuleConfigBase() {
        override fun toJson() = "{}"
        override fun fromJson(json: String) {}
        override fun defaultConfig() = this
    }

    override suspend fun onAwake() {

        _lifecycle.value = ModuleLifecycle.AWAKE
    }
    override suspend fun onStart() { _lifecycle.value = ModuleLifecycle.STARTED }
    override suspend fun onDestroy() { _lifecycle.value = ModuleLifecycle.DESTROYED }
    override suspend fun onConfigChanged(newConfig: ModuleConfigBase) {}
    override suspend fun checkAvailability(): Boolean = true
    override fun markDisabled() { _lifecycle.value = ModuleLifecycle.DISABLED }

    override fun contributions(): List<com.zero.v2.core.moduleapi.ToolContribution> =
        com.zero.v2.core.moduleapi.toolDeclarations(metadata.id) {
        val functionRegistry = this
        functionRegistry.register(
            FunctionDefinition(
                name = "set_alarm",
                description = "设置系统闹钟（打开用户手机闹钟应用并填入时间）。适用于叫醒、会议提醒等场景。",
                parameters = mapOf(
                    "type" to "object",
                    "properties" to mapOf(
                        "hour" to mapOf("type" to "number",
                            "description" to "小时 0-23（24小时制）"),
                        "minute" to mapOf("type" to "number",
                            "description" to "分钟 0-59"),
                        "message" to mapOf("type" to "string",
                            "description" to "闹钟标签（如：起床、开会）")
                    ),
                    "required" to listOf("hour", "minute")
                )
            ),
            metadata.id, permission = ActionPermission.CONFIRM
        ) { args ->
            val hour = ((args["hour"] as? Number)?.toInt() ?: -1).coerceIn(0, 23)
            val minute = ((args["minute"] as? Number)?.toInt() ?: -1).coerceIn(0, 59)
            val message = args["message"] as? String
            setAlarm(hour, minute, message)
        }

        functionRegistry.register(
            FunctionDefinition(
                name = "set_timer",
                description = "设置系统计时器（倒计时提醒）。适用于烹饪计时、番茄钟、休息提醒。",
                parameters = mapOf(
                    "type" to "object",
                    "properties" to mapOf(
                        "seconds" to mapOf("type" to "number",
                            "description" to "倒计时秒数（如 300 = 5分钟）"),
                        "message" to mapOf("type" to "string",
                            "description" to "计时器标签")
                    ),
                    "required" to listOf("seconds")
                )
            ),
            metadata.id, permission = ActionPermission.CONFIRM
        ) { args ->
            val seconds = ((args["seconds"] as? Number)?.toInt() ?: 0).coerceIn(1, 86400)
            val message = args["message"] as? String
            setTimer(seconds, message)
        }
    }

    // ═══ FC 实现 ═══

    private fun setAlarm(hour: Int, minute: Int, message: String?): FunctionResult {
        return try {
            val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
                putExtra(AlarmClock.EXTRA_HOUR, hour)
                putExtra(AlarmClock.EXTRA_MINUTES, minute)
                message?.let { putExtra(AlarmClock.EXTRA_MESSAGE, it) }
                putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            traceManager.record(TRACE_ID, NodeType.SYSTEM_EVENT, metadata.id,
                "alarm_set: ${"%02d".format(hour)}:${"%02d".format(minute)} msg=${message?.take(20)}")
            FunctionResult.success(
                "⏰ 闹钟已设置 ${"%02d".format(hour)}:${"%02d".format(minute)}" +
                        (message?.let { "「$it」" } ?: "") +
                        "（部分闹钟应用可能需用户确认）")
        } catch (e: Exception) {
            traceManager.record(TRACE_ID, NodeType.ERROR, metadata.id,
                "alarm_failed: ${e.message?.take(40)}")
            FunctionResult.failure("❌ 设置闹钟失败: ${e.message?.take(50)}——请检查手机是否有闹钟应用")
        }
    }

    private fun setTimer(seconds: Int, message: String?): FunctionResult {
        return try {
            val intent = Intent(AlarmClock.ACTION_SET_TIMER).apply {
                putExtra(AlarmClock.EXTRA_LENGTH, seconds)
                putExtra(AlarmClock.EXTRA_MESSAGE, message ?: "Zero 计时")
                putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            traceManager.record(TRACE_ID, NodeType.SYSTEM_EVENT, metadata.id,
                "timer_set: ${seconds}s msg=${message?.take(20)}")
            FunctionResult.success("⏱ 计时器已设置 ${seconds}s（${seconds / 60} 分钟）")
        } catch (e: Exception) {
            traceManager.record(TRACE_ID, NodeType.ERROR, metadata.id,
                "timer_failed: ${e.message?.take(40)}")
            FunctionResult.failure("❌ 设置计时器失败: ${e.message?.take(50)}")
        }
    }
}
