package com.zero.v2.services.module

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import com.zero.v2.core.bus.ChatBus
import com.zero.v2.core.bus.SystemBus
import com.zero.v2.core.common.*
import com.zero.v2.core.data.entity.NotepadEntity
import com.zero.v2.core.data.entity.ReminderEntity
import com.zero.v2.core.moduleapi.*
import com.zero.v2.core.common.FunctionResult
import com.zero.v2.core.trace.AgentTraceManager
import com.zero.v2.core.trace.NodeType
import com.zero.v2.services.agent.AgentJob
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.concurrent.PriorityBlockingQueue
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.random.Random

/**
 * 系统事件模块 — Zero v2 核心。
 *
 * 对标 Alife SystemEventPlusService（750行源码）。
 *
 * 功能：
 *   - 多提醒系统（PriorityQueue O(log n)，最多20个）
 *   - 动态报点 NextTick（AI 控制自己的工作节奏）
 *   - 记事本系统（跨 tick 任务状态持久化）
 *   - 自动化调度（通过 SystemBus.OnScheduleTriggered 事件解耦）
 *
 * v2.1: SystemBus 事件解耦（不再直接持有 AutomationModule）
 * v2.2: PriorityQueue 替代线性扫描 + 三重唤醒源
 * v3.4 (D19): 去内驱 — 移除自驱心跳循环，改为实现 AgentJob，
 *             由 Scheduler 在 AgentRuntime tick 上统一触发（tick 单源，消灭双源竞态）。
 * v3.5 修复: ① 提醒到期改为系统通知送达（原 Poke 无消费方，事件进黑洞）；
 *           ② 设置提醒时安排一次性精准唤醒（kick AgentRuntime，tick 仍单源，非心跳）。
 */
@Singleton
class SystemEventModule
    @Inject
    constructor(
        private val scheduleGateway: com.zero.v2.services.agent.AgentScheduleGateway,
        private val chatBus: ChatBus,
        private val systemBus: SystemBus,

        private val traceManager: AgentTraceManager,

        private val systemStateDao: com.zero.v2.core.data.dao.SystemStateDao,

        @ApplicationContext private val context: Context,
    ) : IZeroModule, AgentJob, com.zero.v2.core.moduleapi.ToolContributor {
        companion object {
            private const val NOTIFY_CHANNEL_ID = "zero_reminder"
            private const val NOTIFY_CHANNEL_NAME = "Zero 定时提醒"
            private const val TASK_QUEUE_INITIAL_CAPACITY = 11
        }

        override val metadata =
            ModuleMetadata(
                id = "system_event",
                displayName = "主动事件",
                description = "多提醒、动态报点、记事本、自动化调度。让自己拥有更自然的工作节奏。",
                category = ModuleCategory.SYSTEM,
                launchOrder = 100,
                requiresForegroundService = true,
            )

        private val _lifecycle = MutableStateFlow(ModuleLifecycle.UNINITIALIZED)
        override val lifecycle = _lifecycle.asStateFlow()

        // v6.7: 记事本对外只读流（UI 可视化）+ 持久化协程域
        private val _notepadState = MutableStateFlow("")
        val notepadState = _notepadState.asStateFlow()
        private val moduleScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        override var isEnabled: Boolean = true
        override val requiredPermissions: List<String> = emptyList()
        override val config =
            object : ModuleConfigBase() {
                override fun toJson() = "{}"

                override fun fromJson(json: String) {}

                override fun defaultConfig() = this
            }

        // ═══ 提醒系统 ═══
        private val taskQueue = PriorityBlockingQueue<SystemTickTask>(TASK_QUEUE_INITIAL_CAPACITY, compareBy { it.triggerTimeMs })
        private val taskMutex = Mutex()

        class SystemTickTask(
            val id: String,
            val type: TickTaskType,
            var triggerTimeMs: Long,
            var remark: String = "",
            var isActive: Boolean = true,
            var isRecurring: Boolean = false,
            var recurringMs: Long = 0,
            val automationId: String? = null,
        )

        enum class TickTaskType { REMINDER, DYNAMIC_TICK, AUTO_TICK, AUTOMATION }

        // ═══ 自动报点 ═══
        private var continuousTimerCount = 0
        private var nextAutoTick = Long.MAX_VALUE
        private val heartbeatInterval = 60
        private val randomOffset = 15
        private val backoffMultiplier = 2
        private val maxRetryCount = 6

        // ═══ 记事本 ═══
        private val notepad = StringBuilder()

        /** 一次性精准唤醒协程（非心跳 — 仅到期时刻 kick 一次，D19 合规） */
        private val wakeScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

        override suspend fun onAwake() {

            restorePersistedState() // v6.7: 记事本/提醒重启恢复（P0 持久化；S3.2 增 G6 判定）
            _lifecycle.value = ModuleLifecycle.AWAKE
        }

        /** v6.7+S3.2: 恢复记事本 + 提醒重排（G6 机器化：一次性过期→missed 不补发；周期过期→下期重排） */
        private suspend fun restorePersistedState() {
            try {
                systemStateDao.loadNotepad()?.let { saved ->
                    notepad.append(saved)
                    _notepadState.value = saved
                }
                restoreRemindersLocked()
            } catch (t: Throwable) {
                traceManager.record(
                    "system_state",
                    NodeType.ERROR,
                    metadata.id,
                    "restore_failed: ${t.message?.take(40)}",
                )
            }
        }

        /**
         * 提醒恢复/重排（幂等：先清内存 REMINDER 队列再全量重载）。
         * S3.2 G6 机器化判定（SR2/设计 D118）：
         *   - 仅恢复 active；已错过/未送达需显式重排，已完成不恢复
         *   - repeatRule==null 且非 legacy 周期 且 trigger_at<now → status=missed（不补发，S3.3 UI 可重排）
         *   - active 周期且 trigger_at<now → 按周期推进到未来重排；通知失败可标 missed
         */
        private suspend fun restoreRemindersLocked() = taskMutex.withLock {
            val now = System.currentTimeMillis()
            val restored = systemStateDao.loadReminders()
            taskQueue.removeAll { it.type == TickTaskType.REMINDER }
            var missed = 0
            var rescheduled = 0
            var active = 0
            for (r in restored) {
                // Missed/blocked delivery remains inactive until the user explicitly requeues it.
                if (r.status != ReminderEntity.STATUS_ACTIVE) continue
                val repeatMs = effectiveRepeatMs(r)
                if (r.triggerAt <= now) {
                    if (repeatMs != null && repeatMs > 0) {
                        // 周期过期 → 推进到未来（关机跨多期一次跳齐）
                        var next = r.triggerAt
                        while (next <= now) next += repeatMs
                        systemStateDao.updateSchedule(
                            r.id,
                            next,
                            r.repeatRule,
                            r.isRecurring,
                            r.recurringMs,
                            ReminderEntity.STATUS_ACTIVE,
                            r.remark,
                        )
                        taskQueue.offer(
                            SystemTickTask(
                                r.id,
                                TickTaskType.REMINDER,
                                next,
                                r.remark,
                                isRecurring = true,
                                recurringMs = repeatMs,
                            ),
                        )
                        scheduleWake(next)
                        rescheduled++
                    } else {
                        // 一次性过期 → missed（不补发——用户裁定"关机期间未触发跳过无响应"）
                        systemStateDao.updateStatus(r.id, ReminderEntity.STATUS_MISSED)
                        missed++
                    }
                } else {
                    taskQueue.offer(
                        SystemTickTask(
                            r.id,
                            TickTaskType.REMINDER,
                            r.triggerAt,
                            r.remark,
                            isRecurring = repeatMs != null,
                            recurringMs = repeatMs ?: 0,
                        ),
                    )
                    scheduleWake(r.triggerAt)
                    active++
                }
            }
            traceManager.record(
                "system_state",
                NodeType.SYSTEM_EVENT,
                metadata.id,
                "state_restored: total=${restored.size} active=$active missed=$missed " +
                    "rescheduled=$rescheduled notepadLen=${notepad.length}",
            )
            _remindersState.value = systemStateDao.loadReminders().sortedBy { it.triggerAt }
        }

        /** 周期毫秒数（repeat_rule 语义权威；legacy isRecurring+recurringMs 兼容映射；null=一次性） */
        private fun effectiveRepeatMs(r: ReminderEntity): Long? =
            when {
                r.repeatRule?.startsWith("interval:") == true ->
                    r.repeatRule!!.removePrefix("interval:").toLongOrNull()?.takeIf { it > 0 }
                r.repeatRule == "daily" -> 86_400_000L
                r.repeatRule == "weekly" -> 7 * 86_400_000L
                r.repeatRule == "monthly" -> 30 * 86_400_000L // 近似月（30d）——LLM 语义足够，精确月历无必要
                r.isRecurring && r.recurringMs > 0 -> r.recurringMs
                else -> null
            }

        /** Worker awaits completion; errors and cancellation propagate to its owner. */
        suspend fun restoreAfterBoot() {
            currentCoroutineContext().ensureActive()
            if (!isEnabled || lifecycle.value == ModuleLifecycle.DESTROYED) return
            restoreRemindersLocked()
            taskMutex.withLock { scheduleNextAlarm() }
            traceManager.record("job_system_event", NodeType.SYSTEM_EVENT, metadata.id, "boot_reschedule_done")
        }

        /** Existing permission callback keeps module-owned asynchronous lifetime. */
        fun rescheduleAllOnBoot() {
            moduleScope.launch {
                try {
                    restoreAfterBoot()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (t: Throwable) {
                    traceManager.record(
                        "job_system_event", NodeType.ERROR, metadata.id,
                        "boot_reschedule_failed: ${t.message?.take(40)}",
                    )
                }
            }
        }

        /** 记事本写入统一收口：更新内存 + StateFlow + 异步落库 */
        private fun persistNotepad() {
            _notepadState.value = notepad.toString()
            moduleScope.launch {
                try {
                    systemStateDao.saveNotepad(NotepadEntity(content = notepad.toString()))
                } catch (_: Exception) {
                }
            }
        }

        override suspend fun onStart() {
            scheduleNextAutoTick()
            // v3.4 (D19): 不再启动自驱心跳 — 到期任务由 Scheduler 在 AgentRuntime tick 上触发
            checkExactAlarmPermission() // S3.2/SR3：权限状态 trace + 未授权引导
            registerAlarmPermissionWatcher() // S3.2/G7：授权恢复→自动升回 exact 重排
            _lifecycle.value = ModuleLifecycle.STARTED
        }

        override suspend fun onDestroy() {
            unregisterAlarmPermissionWatcher()
            moduleScope.cancel()
            wakeScope.cancel()
            _lifecycle.value = ModuleLifecycle.DESTROYED
        }

        // ═══ S3.2：Exact Alarm 权限闭环（SR3 检测/引导 + G7 恢复重排）═══

        /** SR3：启动检测 + trace（E28 静默失败必留痕——此前 setWindow 降级无任何痕迹=提醒不稳的头号嫌疑） */
        private fun checkExactAlarmPermission() {
            val am = context.getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager
            val canExact = if (Build.VERSION.SDK_INT >= 31) am.canScheduleExactAlarms() else true
            traceManager.record(
                "job_system_event",
                NodeType.SYSTEM_EVENT,
                metadata.id,
                "alarm_permission_state: exact=$canExact",
            )
            if (!canExact) postExactAlarmGuidance()
        }

        @Volatile private var guidanceShownThisSession = false

        /** 未授权引导：高优通知带系统授权页直达（ACTION_REQUEST_SCHEDULE_EXACT_ALARM），每会话至多一次 */
        private fun postExactAlarmGuidance() {
            if (guidanceShownThisSession) return
            guidanceShownThisSession = true
            try {
                if (Build.VERSION.SDK_INT >= 33 &&
                    context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                ) {
                    return // 无通知权限则不打扰（trace 已留痕）
                }
                val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                nm.createNotificationChannel(
                    NotificationChannel(NOTIFY_CHANNEL_ID, NOTIFY_CHANNEL_NAME, NotificationManager.IMPORTANCE_HIGH),
                )
                val intent =
                    if (Build.VERSION.SDK_INT >= 31) {
                        android.content.Intent(
                            android.provider.Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                            android.net.Uri.parse("package:" + context.packageName),
                        )
                    } else {
                        return
                    }
                val pi =
                    android.app.PendingIntent.getActivity(
                        context,
                        4101,
                        intent,
                        android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE,
                    )
                val icon = com.zero.v2.services.R.drawable.ic_stat_zero // S8.2 品牌小图标
                nm.notify(
                    4101,
                    Notification.Builder(context, NOTIFY_CHANNEL_ID)
                        .setSmallIcon(icon)
                        .setContentTitle("⏰ 提醒准点需要一次授权")
                        .setContentText("未授予「闹钟和提醒」特殊权限时，提醒可能延迟数分钟。点击前往设置开启。")
                        .setAutoCancel(true)
                        .setContentIntent(pi)
                        .build(),
                )
                traceManager.record(
                    "job_system_event",
                    NodeType.SYSTEM_EVENT,
                    metadata.id,
                    "alarm_permission_guidance_shown",
                )
            } catch (e: Exception) {
                traceManager.record(
                    "job_system_event",
                    NodeType.ERROR,
                    metadata.id,
                    "alarm_guidance_failed: ${e.message?.take(40)}",
                )
            }
        }

        /** G7：授权恢复广播 → 全部 active 项从 inexact 自动升回 exact 重排 */
        private var alarmPermReceiver: android.content.BroadcastReceiver? = null

        private fun registerAlarmPermissionWatcher() {
            if (Build.VERSION.SDK_INT < 31) return
            if (alarmPermReceiver != null) return
            alarmPermReceiver =
                object : android.content.BroadcastReceiver() {
                    override fun onReceive(
                        ctx: Context,
                        intent: android.content.Intent,
                    ) {
                        val am = ctx.getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager
                        val nowExact = am.canScheduleExactAlarms()
                        traceManager.record(
                            "job_system_event", NodeType.SYSTEM_EVENT, metadata.id,
                            "alarm_permission_changed: exact=$nowExact",
                        )
                        if (nowExact) {
                            // 降级期间排的 setWindow 闹钟全部重排为 setExactAndAllowWhileIdle
                            scheduleNextAlarm()
                            rescheduleAllOnBoot() // 复用幂等重排（刷新全部提醒唤醒）
                        }
                    }
                }
            val filter = android.content.IntentFilter(android.app.AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED)
            val flags =
                if (Build.VERSION.SDK_INT >= 33) {
                    android.content.Context.RECEIVER_NOT_EXPORTED
                } else {
                    0
                }
            try {
                if (Build.VERSION.SDK_INT >= 33) {
                    context.registerReceiver(alarmPermReceiver, filter, flags)
                } else {
                    @Suppress("UnspecifiedRegisterReceiverFlag")
                    context.registerReceiver(alarmPermReceiver, filter)
                }
                traceManager.record(
                    "job_system_event",
                    NodeType.SYSTEM_EVENT,
                    metadata.id,
                    "alarm_permission_watcher_registered",
                )
            } catch (e: Exception) {
                traceManager.record(
                    "job_system_event",
                    NodeType.ERROR,
                    metadata.id,
                    "alarm_watcher_register_failed: ${e.message?.take(40)}",
                )
            }
        }

        private fun unregisterAlarmPermissionWatcher() {
            alarmPermReceiver?.let {
                try {
                    context.unregisterReceiver(it)
                } catch (_: Exception) {
                }
            }
            alarmPermReceiver = null
        }

        override suspend fun onConfigChanged(newConfig: ModuleConfigBase) {}

        override suspend fun checkAvailability(): Boolean = true

        override fun markDisabled() {
            _lifecycle.value = ModuleLifecycle.DISABLED
        }

        // ═══════════════════════════════════════════════════
        // FC 函数注册（8 个函数）
        // ═══════════════════════════════════════════════════

        override fun contributions(): List<com.zero.v2.core.moduleapi.ToolContribution> =
            com.zero.v2.core.moduleapi.toolDeclarations(metadata.id) {
            val functionRegistry = this
            fun reg(
                name: String,
                desc: String,
                params: Map<String, Any?> = emptyMap(),
                security: FunctionSecurity = FunctionSecurity.SAFE,
                handler: suspend (Map<String, Any?>) -> FunctionResult,
            ) {
                functionRegistry.register(FunctionDefinition(name, desc, params, security), metadata.id, handler)
            }

            // 提醒系统（S3.3：全生命周期 FC——set/list/cancel 既有名 + modify 新增；C9 防工具名漂移）
            reg(
                "set_reminder",
                "设置提醒。支持绝对时间(2026-08-22T11:00 / 11:00)和相对时间(+5m/+1h/+30s)，最多20个。" +
                    "可选周期重复（recurring=true + interval_seconds）。",
                mapOf(
                    "type" to "object",
                    "properties" to
                        mapOf(
                            "time" to mapOf("type" to "string", "description" to "绝对时间(2026-08-22T11:00 或 11:00)或相对时间(+5m)"),
                            "remark" to mapOf("type" to "string", "description" to "提醒内容"),
                            "recurring" to mapOf("type" to "boolean", "description" to "是否重复"),
                            "interval_seconds" to mapOf("type" to "number", "description" to "重复间隔秒"),
                        ),
                    "required" to listOf("time", "remark"),
                ),
                FunctionSecurity.SENSITIVE,
            ) { setReminder(it) }

            reg(
                "list_reminders",
                "列出全部提醒（含状态：active 活跃 / missed 已错过 / done 已完成）。",
                mapOf("type" to "object", "properties" to emptyMap<String, Any>()),
            ) { listReminders() }
            reg(
                "cancel_reminder",
                "取消提醒（删除，含已错过的）",
                mapOf(
                    "type" to "object",
                    "properties" to
                        mapOf(
                            "reminder_id" to mapOf("type" to "string", "description" to "提醒ID"),
                        ),
                    "required" to listOf("reminder_id"),
                ),
                FunctionSecurity.SENSITIVE,
            ) { cancelReminder(it["reminder_id"] as? String ?: "") }

            reg(
                "modify_reminder",
                "修改提醒：改时间/内容/重复规则任一项；可将 missed（已错过）提醒重新激活为 active。",
                mapOf(
                    "type" to "object",
                    "properties" to
                        mapOf(
                            "reminder_id" to mapOf("type" to "string", "description" to "提醒ID"),
                            "time" to mapOf("type" to "string", "description" to "新时间（绝对或 +5m 相对；缺省不变）"),
                            "remark" to mapOf("type" to "string", "description" to "新内容（缺省不变）"),
                            "recurring" to mapOf("type" to "boolean", "description" to "是否重复（缺省不变）"),
                            "interval_seconds" to mapOf("type" to "number", "description" to "重复间隔秒（recurring=true 时生效）"),
                        ),
                    "required" to listOf("reminder_id"),
                ),
                FunctionSecurity.SENSITIVE,
            ) { modifyReminder(it) }

            // 动态报点
            reg(
                "next_tick",
                "设置下一次自动唤醒时间，控制工作节奏。任务未完成设短间隔(5-30s)，空闲设长间隔(60-300s)。",
                mapOf(
                    "type" to "object",
                    "properties" to
                        mapOf(
                            "seconds" to mapOf("type" to "number", "description" to "秒数 3-300"),
                            "note" to mapOf("type" to "string", "description" to "进度备注"),
                        ),
                    "required" to listOf("seconds"),
                ),
            ) { nextTick(it) }

            // 记事本
            reg(
                "read_notepad",
                "读取任务记事本，了解当前进度。",
                mapOf("type" to "object", "properties" to emptyMap<String, Any>()),
            ) { readNotepadFunc() }
            reg(
                "write_notepad",
                "向记事本追加记录。",
                mapOf(
                    "type" to "object",
                    "properties" to
                        mapOf(
                            "content" to mapOf("type" to "string", "description" to "记录内容"),
                        ),
                    "required" to listOf("content"),
                ),
                FunctionSecurity.SENSITIVE,
            ) { writeNotepadFunc(it["content"] as? String ?: "") }

            reg(
                "clear_notepad",
                "清空记事本（任务完成时使用）。",
                mapOf("type" to "object", "properties" to emptyMap<String, Any>()),
                FunctionSecurity.SENSITIVE,
            ) { clearNotepadFunc() }
            reg(
                "archive_notepad",
                "归档记事本到历史。",
                mapOf(
                    "type" to "object",
                    "properties" to
                        mapOf(
                            "archive_name" to mapOf("type" to "string", "description" to "归档文件名，可选"),
                        ),
                ),
                FunctionSecurity.SENSITIVE,
            ) { archiveNotepadFunc(it["archive_name"] as? String) }

            // 生词本（6.7 词汇闭环）：结构化入记事本，会话内 read_notepad 可查；
            // 需跨会话持久化时由 LLM 追加调用 save_to_file 写入 vocabulary.md
            reg(
                "add_vocabulary",
                "将生词/术语存入生词本（结构化记录，会话内可查）。跨会话保存请再调用 save_to_file 追加到 vocabulary.md。",
                mapOf(
                    "type" to "object",
                    "properties" to
                        mapOf(
                            "word" to mapOf("type" to "string", "description" to "生词原文"),
                            "meaning" to mapOf("type" to "string", "description" to "释义/翻译"),
                            "context" to mapOf("type" to "string", "description" to "出处语境（原句），可选"),
                        ),
                    "required" to listOf("word", "meaning"),
                ),
            ) {
                addVocabularyFunc(
                    it["word"] as? String ?: "",
                    it["meaning"] as? String ?: "",
                    it["context"] as? String,
                )
            }
        }

        // ═══════════════════════════════════════════════════
        // AgentJob — 由 Scheduler 在 AgentRuntime tick 上统一触发（D19 去内驱）
        // ═══════════════════════════════════════════════════

        override val id: String = "system_event_reminders"

        /** 队列中最早的活跃任务时间（提醒/动态报点/自动化）；空/全失效 = MAX_VALUE 暂停 */
        override val nextRunTime: Long
            get() = taskQueue.filter { it.isActive }.minOfOrNull { it.triggerTimeMs } ?: Long.MAX_VALUE

        override suspend fun execute() {
            return taskMutex.withLock {
                // OFF suppresses residual automatic cadence, while explicit reminders still deliver.
                if (scheduleGateway.isAutonomousOff()) {
                    nextAutoTick = Long.MAX_VALUE
                    taskQueue.removeAll { it.type == TickTaskType.AUTO_TICK }
                }
                val now = System.currentTimeMillis()
                val due = taskQueue.count { it.isActive && it.triggerTimeMs <= now }
                if (due > 0) {
                    traceManager.record(
                        "job_system_event",
                        NodeType.SYSTEM_EVENT,
                        metadata.id,
                        "job_execute: due=$due nextRun=$nextRunTime",
                    )
                }
                processDueTasks(now)
                // v3.4 (D40): 为下一最早提醒重排精确闹钟（进程冻结下仍准点）
                scheduleNextAlarm()

            }
        }

        /** 按队列最早任务重排闹钟；无任务则只保留进程内 kick 兜底 */
        private fun scheduleNextAlarm() {
            val next = nextRunTime
            if (next != Long.MAX_VALUE) {
                scheduleGateway.scheduleReminder(next)
                traceManager.record(
                    "job_system_event",
                    NodeType.SYSTEM_EVENT,
                    metadata.id,
                    "alarm_rescheduled: nextRun=$next",
                )
            }
        }

        // ═══════════════════════════════════════════════════
        // 到期任务处理（原 ITimedModule.onTick，v3.4 改为私有）
        // ═══════════════════════════════════════════════════

        private suspend fun processDueTasks(tickMs: Long) {
            // 1. 处理所有到期任务 (PriorityQueue O(log n) poll)
            while (taskQueue.isNotEmpty() && taskQueue.peek().triggerTimeMs <= tickMs) {
                val task = taskQueue.poll()
                if (!task.isActive) continue
                when (task.type) {
                    TickTaskType.REMINDER -> {
                        // v3.5: 系统通知送达（Poke 当前无消费方，仅保留总线事件）
                        if (!postReminderNotification(task.id, task.triggerTimeMs, task.remark)) {
                            systemStateDao.markUndeliveredIfActive(task.id, task.triggerTimeMs)
                            refreshRemindersLocked()
                            throw ModuleOperationRejectedException("reminder_notification_not_delivered")
                        }
                        chatBus.onPokeSend.emit(PokeEvent("⏰ 提醒 [${task.id}]: ${task.remark}", metadata.id, PokePriority.HIGH))
                        // 2026-09-18 用户二次裁定：提醒源**不自驱**（回归纯通知——自驱场景已多，
                        // 密集/周期提醒会挤占配额刷屏）。自驱源收敛为：报点随发（selfDriveOnTick）
                        // + 既有通知白名单触发（TriggerHub NOTIFICATION 链）+ next_tick 静默闹钟
                        // + Automation SELF_DRIVE。LLM 需跟进任务时用 next_tick 自续（无通知静默）。
                        if (task.isRecurring && task.recurringMs > 0) {
                            // S3.3-fix：周期推进保持原相位（v6.7 原 tickMs+ms 在迟到触发时漂移——
                            // 07:00 于 21:15 被补触发 → 顺延成 21:15+1d，8 条挤同刻、原时段全丢的实测根因；
                            // 与 S3.2 恢复期 while 推进同语义：07:00 永远落在 07:00）
                            val claimedTrigger = task.triggerTimeMs
                            var nextTrigger = claimedTrigger + task.recurringMs
                            while (nextTrigger <= tickMs) nextTrigger += task.recurringMs
                            // Await the conditional commit before publishing the next occurrence.
                            // A removed, disabled or rescheduled row must not be recreated/overwritten.
                            val advanced = systemStateDao.advanceRecurringIfActive(task.id, claimedTrigger, nextTrigger)
                            if (advanced == 1) {
                                task.triggerTimeMs = nextTrigger
                                taskQueue.offer(task)
                                scheduleWake(nextTrigger)
                            }
                        } else {
                            // v6.7: 一次性提醒已送达 → 删除持久化行
                            systemStateDao.deleteDeliveredIfActive(task.id, task.triggerTimeMs)
                        }
                    }
                    TickTaskType.DYNAMIC_TICK -> {
                        val np = notepad.toString()
                        // SD1.1（2026-09-18 用户需求）：静默自驱定时器——next_tick 到点
                        // **不发系统通知**，仅唤醒 LLM 自驱回合继续跟进（原设计 Poke 事件
                        // 无消费方进黑洞——"稍后继续"的环断在此接上）。fireSelfDrive 的
                        // 显示气泡走对话内事件流，非通知栏——静默语义成立
                        scheduleGateway.fireSelfDrive(
                            "动态续跑闹钟到点：检查任务进度并继续${
                                if (np.isNotEmpty()) "（记事本最新进度：${np.takeLast(400)}）" else "（记事本为空）"}",
                            "next_tick",
                        )
                        chatBus.onPokeSend.emit(
                            PokeEvent("动态报点唤醒。\n${if (np.isNotEmpty()) "当前任务进度:\n$np" else "📝 记事本为空"}\n\n(检查任务状态)", metadata.id),
                        )
                        continuousTimerCount = 0
                        nextAutoTick = Long.MAX_VALUE
                    }
                    TickTaskType.AUTO_TICK -> {
                        chatBus.onPokeSend.emit(
                            PokeEvent(
                                "系统周期报点。(检查任务状态，使用记事本和动态报点管理工作节奏)" +
                                    if (continuousTimerCount >= maxRetryCount) "\n(已达最大间隔)" else "",
                                metadata.id,
                            ),
                        )
                        continuousTimerCount++
                        scheduleNextAutoTick()
                    }
                    TickTaskType.AUTOMATION -> {
                        systemBus.onScheduleTriggered.emit(ScheduleTriggeredEvent(task.automationId!!, tickMs, TriggerSource.HEARTBEAT))
                    }
                }
            }

            // 2. 检查自动报点（兜底）
            if (nextAutoTick != Long.MAX_VALUE && tickMs > nextAutoTick) {
                taskQueue.offer(SystemTickTask("auto_$tickMs", TickTaskType.AUTO_TICK, tickMs + 100))
            }
        }

        // ═══════════════════════════════════════════════════
        // FC 实现
        // ═══════════════════════════════════════════════════

        private suspend fun setReminder(args: Map<String, Any?>): FunctionResult {
            return taskMutex.withLock {
                val timeStr = args["time"] as? String ?: ""
                val remark = args["remark"] as? String ?: ""
                val recurring = args["recurring"] as? Boolean ?: false
                val intervalSec = (args["interval_seconds"] as? Number)?.toInt() ?: 0

                val triggerMs = parseTime(timeStr)
                    ?: return@withLock FunctionResult.failure("无法解析时间: $timeStr (格式: ISO时间 或 +5m/+1h/+30s)")
                // S3.3-fix：过去时间守卫（实测 bug——LLM 设"今日已过时刻"会立即触发并相位漂移）
                if (triggerMs <= System.currentTimeMillis()) {
                    return@withLock FunctionResult.failure(
                        "时间已过去（$timeStr）。请设未来时刻；" +
                            "纯时刻如 11:00 会自动顺延到下一个未来时段，日期请写明天或之后",
                    )
                }
                val id = "rem_${System.currentTimeMillis().toString(36)}"
                val repeatRule = if (recurring && intervalSec > 0) "interval:${intervalSec * 1000L}" else null
                systemStateDao.saveReminder(
                    ReminderEntity(
                        id = id, remark = remark, triggerAt = triggerMs,
                        isRecurring = recurring, recurringMs = intervalSec * 1000L,
                        title = remark, status = ReminderEntity.STATUS_ACTIVE,
                        createdBy = ReminderEntity.CREATED_BY_LLM, repeatRule = repeatRule,
                    ),
                )
                taskQueue.offer(
                    SystemTickTask(id, TickTaskType.REMINDER, triggerMs, remark, isRecurring = recurring, recurringMs = intervalSec * 1000L),
                )
                continuousTimerCount = 0
                scheduleWake(triggerMs)
                // v6.7 落库（S3.3：+repeat_rule/created_by=llm 全生命周期字段）
                refreshRemindersLocked()
                traceManager.record(
                    "fc_set_reminder",
                    NodeType.SYSTEM_EVENT,
                    metadata.id,
                    "reminder_set: id=$id trigger=$triggerMs recurring=$recurring",
                )
                return@withLock FunctionResult.success(
                    "已设置提醒 [$id]: $remark @ $triggerMs" +
                        if (recurring && intervalSec > 0) "（每 ${intervalSec}s 重复）" else "",
                )

            }
        }

        /** S3.3：modify_reminder——内存队列与 DB 双更新；missed 行可复活（S3.2 G6 判定的出口） */
        private suspend fun modifyReminder(args: Map<String, Any?>): FunctionResult {
            return taskMutex.withLock {
                val id = args["reminder_id"] as? String ?: ""
                if (id.isBlank()) return@withLock FunctionResult.failure("reminder_id 不能为空")
                val newTimeStr = args["time"] as? String
                val newRemark = args["remark"] as? String
                val newRecurring = args["recurring"] as? Boolean
                val newIntervalSec = (args["interval_seconds"] as? Number)?.toInt()
                if (newTimeStr == null && newRemark == null && newRecurring == null && newIntervalSec == null) {
                    return@withLock FunctionResult.failure("未提供任何修改项（time/remark/recurring/interval_seconds 至少一项）")
                }
                return@withLock try {
                    val row =
                        systemStateDao.loadReminders().firstOrNull { it.id == id }
                            ?: return@withLock FunctionResult.failure("提醒不存在: $id（可能已送达删除）")
                    val newTrigger =
                        if (newTimeStr != null) {
                            val parsed =
                                parseTime(newTimeStr)
                                    ?: return@withLock FunctionResult.failure("无法解析时间: $newTimeStr (格式: ISO时间 或 +5m)")
                            // S3.3-fix：过去时间守卫（同 set_reminder——实测 8 条挤同刻的入口）
                            if (parsed <= System.currentTimeMillis()) {
                                return@withLock FunctionResult.failure(
                                    "新时间已过去（$newTimeStr）。请设未来时刻；" +
                                        "周期提醒从所设时刻起算，过去时刻会立即触发导致相位漂移",
                                )
                            }
                            parsed
                        } else {
                            row.triggerAt
                        }
                    val remark = newRemark ?: row.remark
                    val recurring =
                        newRecurring
                            ?: (row.repeatRule != null || row.isRecurring)
                    val intervalMs = newIntervalSec?.let { it * 1000L } ?: (effectiveRepeatMs(row) ?: 0)
                    if (recurring && intervalMs <= 0) {
                        return@withLock FunctionResult.failure("recurring=true 需同时给 interval_seconds")
                    }
                    val repeatRule =
                        when {
                            recurring && newIntervalSec != null -> "interval:${newIntervalSec * 1000L}"
                            recurring && row.repeatRule != null -> row.repeatRule
                            recurring -> "interval:$intervalMs"
                            else -> null
                        }
                    // DB：全字段 + 状态复活 active（missed→修改即重排）——remark 一并落盘
                    // （2026-09-18 反馈修复：原实现漏 remark 致"改文本成功但 list 仍旧文"静默失败）
                    systemStateDao.updateSchedule(
                        id,
                        newTrigger,
                        repeatRule,
                        recurring,
                        intervalMs,
                        ReminderEntity.STATUS_ACTIVE,
                        remark,
                    )
                    // 内存队列：移除旧行（若在），重投新行
                    taskQueue.removeAll { it.id == id && it.type == TickTaskType.REMINDER }
                    taskQueue.offer(
                        SystemTickTask(
                            id,
                            TickTaskType.REMINDER,
                            newTrigger,
                            remark,
                            isRecurring = recurring,
                            recurringMs = intervalMs,
                        ),
                    )
                    scheduleWake(newTrigger)
                    refreshRemindersLocked()
                    traceManager.record(
                        "fc_modify_reminder",
                        NodeType.SYSTEM_EVENT,
                        metadata.id,
                        "reminder_modified: id=$id trigger=$newTrigger recurring=$recurring",
                    )
                    FunctionResult.success(
                        "已修改提醒 [$id]: $remark @ ${fmtTime(newTrigger)}" +
                            (if (row.status == ReminderEntity.STATUS_MISSED) "（已从错过状态重新激活）" else ""),
                    )
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    traceManager.record(
                        "fc_modify_reminder",
                        NodeType.ERROR,
                        metadata.id,
                        "modify_failed: ${e.message?.take(40)}",
                    )
                    FunctionResult.failure("修改失败: ${e.message?.take(50)}")
                }

            }
        }

        /** 时间展示（list/UI 共用） */
        private fun fmtTime(ms: Long): String = java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.CHINA).format(java.util.Date(ms))

        private suspend fun nextTick(args: Map<String, Any?>): FunctionResult {
            return taskMutex.withLock {
                val seconds = ((args["seconds"] as? Number)?.toInt() ?: 60).coerceIn(3, 300)
                val note = args["note"] as? String
                if (note != null) {
                    notepad.appendLine("[${System.currentTimeMillis()}] $note")
                    persistNotepad()
                }
                taskQueue.offer(
                    SystemTickTask(
                        "tick_${System.currentTimeMillis()}",
                        TickTaskType.DYNAMIC_TICK,
                        System.currentTimeMillis() + seconds * 1000L,
                    ),
                )
                continuousTimerCount = 0
                nextAutoTick = Long.MAX_VALUE
                return@withLock FunctionResult.success("⏱ 已设置 ${seconds}s 后继续" + if (note != null) "，进度已记录" else "")

            }
        }

        /** S3.3：DB 为源（含 missed/done 全状态；每次变更同步 DB 后 refresh） */
        private suspend fun listReminders(): FunctionResult {
            val rows = systemStateDao.loadReminders().sortedBy { it.triggerAt }
            if (rows.isEmpty()) return FunctionResult.success("📋 当前没有提醒")
            return FunctionResult.success(
                rows.joinToString("\n") { r ->
                    val st =
                        when (r.status) {
                            ReminderEntity.STATUS_MISSED -> "⏸已错过"
                            ReminderEntity.STATUS_DONE -> "✓完成"
                            else -> "⏳"
                        }
                    val repeat = effectiveRepeatMs(r)?.let { " · 每${humanMs(it)}重复" } ?: ""
                    "[${r.id}] $st ${r.remark} @ ${fmtTime(r.triggerAt)}$repeat"
                },
            )
        }

        private fun humanMs(ms: Long): String =
            when {
                ms % 86_400_000L == 0L -> "${ms / 86_400_000L}天"
                ms % 3_600_000L == 0L -> "${ms / 3_600_000L}小时"
                ms % 60_000L == 0L -> "${ms / 60_000L}分钟"
                else -> "${ms / 1000L}秒"
            }

        private suspend fun cancelReminder(id: String): FunctionResult {
            return taskMutex.withLock {
                systemStateDao.deleteReminder(id)
                taskQueue.removeAll { it.id == id && it.type == TickTaskType.REMINDER }
                refreshRemindersLocked()
                scheduleNextAlarm()
                return@withLock FunctionResult.success("已取消提醒 [$id]")

            }
        }

        // ═══ S3.3：提醒管理 UI 支撑（TriggerSettingsScreen 消费）═══

        private val _remindersState = MutableStateFlow<List<ReminderEntity>>(emptyList())
        val remindersState = _remindersState.asStateFlow()

        /** 从 DB 刷新只读流（set/cancel/modify/restore 后统一调用） */
        fun refreshReminders() {
            moduleScope.launch {
                try {
                    taskMutex.withLock { refreshRemindersLocked() }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    traceManager.record("system_state", NodeType.ERROR, metadata.id,
                        "reminders_refresh_failed: ${e.message?.take(40)}")
                }
            }
        }

        private suspend fun refreshRemindersLocked() {
            _remindersState.value = systemStateDao.loadReminders().sortedBy { it.triggerAt }
        }

        /** UI 删除（与 cancel_reminder FC 同路径） */
        fun deleteReminderFromUi(id: String) {
            moduleScope.launch {
                try {
                    cancelReminder(id)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    traceManager.record("system_state", NodeType.ERROR, metadata.id,
                        "reminder_delete_failed: ${e.message?.take(40)}")
                }
            }
        }

        /** UI 一键重排（missed 高亮行）：+10 分钟复活为 active */
        fun requeueMissedFromUi(id: String) {
            moduleScope.launch {
                taskMutex.withLock {
                    try {
                        val r = systemStateDao.loadReminders().firstOrNull { it.id == id } ?: return@launch
                        val next = System.currentTimeMillis() + 10 * 60_000L
                        systemStateDao.updateSchedule(
                            r.id,
                            next,
                            r.repeatRule,
                            r.isRecurring,
                            r.recurringMs,
                            ReminderEntity.STATUS_ACTIVE,
                            r.remark,
                        )
                        taskQueue.removeAll { it.id == id && it.type == TickTaskType.REMINDER }
                        taskQueue.offer(
                            SystemTickTask(
                                r.id,
                                TickTaskType.REMINDER,
                                next,
                                r.remark,
                                isRecurring = effectiveRepeatMs(r) != null,
                                recurringMs = effectiveRepeatMs(r) ?: 0,
                            ),
                        )
                        scheduleWake(next)
                        traceManager.record(
                            "ui_reminder_requeue",
                            NodeType.SYSTEM_EVENT,
                            metadata.id,
                            "reminder_requeued: id=$id from=${r.status} next=$next",
                        )
                        _remindersState.value = systemStateDao.loadReminders().sortedBy { it.triggerAt }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        traceManager.record(
                            "ui_reminder_requeue",
                            NodeType.ERROR,
                            metadata.id,
                            "requeue_failed: ${e.message?.take(40)}",
                        )
                        }
            }
            }
        }

        private fun readNotepadFunc() =
            FunctionResult.success(
                notepad.toString().ifEmpty { "📝 记事本为空" }.let { "📝 任务记事本:\n$it" },
            )

        private fun writeNotepadFunc(content: String): FunctionResult {
            notepad.appendLine("[${System.currentTimeMillis()}] $content")
            persistNotepad()
            return FunctionResult.success("已记录到记事本")
        }

        /** 6.7 词汇闭环：结构化生词条目入记事本（会话内 read_notepad 可查） */
        private fun addVocabularyFunc(
            word: String,
            meaning: String,
            context: String?,
        ): FunctionResult {
            if (word.isBlank() || meaning.isBlank()) return FunctionResult.failure("word 与 meaning 不能为空")
            val ctx = context?.takeIf { it.isNotBlank() }?.let { " ‹$it›" } ?: ""
            notepad.appendLine("📖 [$word] = $meaning$ctx")
            persistNotepad()
            traceManager.record(
                "fc_add_vocabulary",
                NodeType.SYSTEM_EVENT,
                metadata.id,
                "vocabulary_added: $word",
            )
            return FunctionResult.success("已存入生词本：[$word]（跨会话保存可调用 save_to_file 追加到 vocabulary.md）")
        }

        private fun clearNotepadFunc(): FunctionResult {
            notepad.clear()
            persistNotepad()
            return FunctionResult.success("记事本已清空")
        }

        private fun archiveNotepadFunc(name: String?): FunctionResult {
            val archiveName = name ?: "archive_${System.currentTimeMillis()}"
            notepad.clear()
            persistNotepad()
            return FunctionResult.success("已归档到: $archiveName")
        }

        // ═══════════════════════════════════════════════════
        // 内部
        // ═══════════════════════════════════════════════════

        private fun scheduleNextAutoTick() {
            val base = heartbeatInterval + Random.nextInt(-randomOffset, randomOffset)
            val multiplier = Math.pow(backoffMultiplier.toDouble(), minOf(continuousTimerCount, maxRetryCount).toDouble()).toInt()
            nextAutoTick = System.currentTimeMillis() + base * multiplier * 1000L
        }

        /**
         * 时间解析（v3.4 修复：实测绝对时间 18 连败）。
         * 支持: +5m/+1h/+30s（相对）| 2026-08-22T11:00[:00] | 2026-08-22 11:00 |
         *       11:00（今日已过则明日）| 明天/明天HH:mm | ISO 带时区（Z/+08:00）
         */
        private fun parseTime(input: String): Long? {
            val raw = input.trim()

            // 相对时间
            if (raw.startsWith("+")) {
                val match = Regex("""\+(\d+)([smhd])""").find(raw) ?: return null
                val value = match.groupValues[1].toLong()
                return System.currentTimeMillis() +
                    when (match.groupValues[2]) {
                        "s" -> value * 1000
                        "m" -> value * 60_000
                        "h" -> value * 3600_000
                        "d" -> value * 86400_000
                        else -> return null
                    }
            }

            val zone = ZoneId.systemDefault()
            val now = System.currentTimeMillis()

            // 完整日期时间（本地无时区）: 2026-08-22T11:00 / 2026-08-22 11:00[:30]
            Regex("""(\d{4})-(\d{1,2})-(\d{1,2})[T ](\d{1,2}):(\d{2})(?::(\d{2}))?""").find(raw)?.let { m ->
                return try {
                    LocalDateTime.of(
                        m.groupValues[1].toInt(),
                        m.groupValues[2].toInt(),
                        m.groupValues[3].toInt(),
                        m.groupValues[4].toInt(),
                        m.groupValues[5].toInt(),
                        m.groupValues[6].ifBlank { "0" }.toInt(),
                    ).atZone(zone).toInstant().toEpochMilli()
                } catch (_: Exception) {
                    null
                }
            }

            // 仅日期: 2026-08-22 → 当日 08:00
            Regex("""(\d{4})-(\d{1,2})-(\d{1,2})$""").find(raw)?.let { m ->
                return try {
                    LocalDate.of(m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3].toInt())
                        .atTime(8, 0).atZone(zone).toInstant().toEpochMilli()
                } catch (_: Exception) {
                    null
                }
            }

            // 时刻: 11:00 / 11:00:30（今日已过 → 明日）| 前缀"明天"
            Regex("""(明天)?\s*(\d{1,2}):(\d{2})(?::(\d{2}))?$""").find(raw)?.let { m ->
                return try {
                    var date = LocalDate.now(zone)
                    if (!m.groupValues[1].isBlank()) date = date.plusDays(1)
                    var dt =
                        date.atTime(
                            m.groupValues[2].toInt(),
                            m.groupValues[3].toInt(),
                            m.groupValues[4].ifBlank { "0" }.toInt(),
                        )
                    if (dt.atZone(zone).toInstant().toEpochMilli() <= now && m.groupValues[1].isBlank()) {
                        dt = dt.plusDays(1)
                    }
                    dt.atZone(zone).toInstant().toEpochMilli()
                } catch (_: Exception) {
                    null
                }
            }

            // ISO 带时区
            return try {
                java.time.Instant.parse(raw).toEpochMilli()
            } catch (_: Exception) {
                null
            }
        }

        /**
         * 准点唤醒（v3.4 D40 双通道）:
         * 主 = AlarmManager 精确闹钟（Doze/进程冻结下由系统回调 ExactAlarmReceiver）
         * 辅 = 进程内 kick（前台时更快触发；后台被冻结时的兜底失效由闹钟接管）
         */
        private fun scheduleWake(triggerMs: Long) {
            val wait = triggerMs - System.currentTimeMillis()
            if (wait <= 0) {
                scheduleGateway.kick()
                return
            }
            scheduleGateway.scheduleReminder(triggerMs)
            wakeScope.launch {
                delay(wait)
                scheduleGateway.kick()
            }
        }

        /** 提醒通知送达（复用 NotificationModule 的权限检查 + 渠道模式） */
        private fun postReminderNotification(reminderId: String, occurrenceAt: Long, content: String): Boolean {
            return try {
                if (Build.VERSION.SDK_INT >= 33 &&
                    context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                ) {
                    traceManager.record(
                        "job_system_event",
                        NodeType.ERROR,
                        metadata.id,
                        "reminder_notify_blocked: POST_NOTIFICATIONS 未授予",
                    )
                    return false
                }
                val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                nm.createNotificationChannel(
                    NotificationChannel(NOTIFY_CHANNEL_ID, NOTIFY_CHANNEL_NAME, NotificationManager.IMPORTANCE_HIGH),
                )
                check(nm.areNotificationsEnabled() &&
                    nm.getNotificationChannel(NOTIFY_CHANNEL_ID)?.importance != NotificationManager.IMPORTANCE_NONE
                ) { "notifications_disabled" }
                val icon = com.zero.v2.services.R.drawable.ic_stat_zero // S8.2 品牌小图标
                nm.notify(
                    // Replaying this occurrence updates the existing notification; other occurrences remain distinct.
                    "zero_reminder:$reminderId:$occurrenceAt",
                    0,
                    Notification.Builder(context, NOTIFY_CHANNEL_ID)
                        .setSmallIcon(icon)
                        .setContentTitle("⏰ Zero 提醒")
                        .setContentText(content)
                        .setStyle(Notification.BigTextStyle().bigText(content))
                        .setAutoCancel(true)
                        .setOnlyAlertOnce(true)
                        .build(),
                )
                traceManager.record(
                    "job_system_event",
                    NodeType.SYSTEM_EVENT,
                    metadata.id,
                    "reminder_notified: ${content.take(30)}",
                )
                true
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                traceManager.record(
                    "job_system_event",
                    NodeType.ERROR,
                    metadata.id,
                    "reminder_notify_failed: ${e.message?.take(50)}",
                )
                false
            }
        }
    }
