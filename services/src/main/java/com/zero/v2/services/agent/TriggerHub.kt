package com.zero.v2.services.agent

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import androidx.core.app.NotificationCompat
import com.zero.v2.core.bus.SystemBus
import com.zero.v2.core.common.ActionPermission
import com.zero.v2.core.common.FunctionDefinition
import com.zero.v2.core.common.TurnSource
import com.zero.v2.core.moduleapi.ConfigField
import com.zero.v2.core.moduleapi.ConfigFieldType
import com.zero.v2.core.moduleapi.IZeroModule
import com.zero.v2.core.moduleapi.ModuleCategory
import com.zero.v2.core.moduleapi.ModuleConfigBase
import com.zero.v2.core.moduleapi.ModuleLifecycle
import com.zero.v2.core.moduleapi.ModuleMetadata
import com.zero.v2.core.common.FunctionResult
import com.zero.v2.core.trace.AgentTraceManager
import com.zero.v2.core.trace.NodeType
import com.zero.v2.services.realtime.ChatEngineBridge
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.util.Calendar
import javax.inject.Inject
import javax.inject.Singleton

/** TriggerHub 配置 — A3a 全量设备源参数（D17 全显式定界 + 4c 注释） */
class TriggerHubConfig : ModuleConfigBase() {
    @ConfigField(
        label = "通知触发开关",
        fieldType = ConfigFieldType.TOGGLE,
        description = "白名单内应用来通知时让 Zero 自主判断是否提醒/代回（推荐开；夜间照常）",
    )
    var notificationTriggerEnabled: Boolean = true

    @ConfigField(
        label = "充电满触发",
        fieldType = ConfigFieldType.TOGGLE,
        description = "充电完成时告知",
    )
    var batteryFullEnabled: Boolean = false

    @ConfigField(
        label = "电量低触发",
        fieldType = ConfigFieldType.TOGGLE,
        description = "低于阈值时提醒",
    )
    var batteryLowEnabled: Boolean = false

    @ConfigField(
        label = "电量低阈值 (%)",
        fieldType = ConfigFieldType.NUMBER,
        min = 5.0,
        max = 30.0,
        description = "建议 20；回充到 90% 后才会再次提醒（防反复轰炸）",
    )
    var batteryLowThreshold: Int = 20

    @ConfigField(
        label = "夜间亮屏触发",
        fieldType = ConfigFieldType.TOGGLE,
        description = "深夜持续亮屏时关怀提醒",
    )
    var nightScreenEnabled: Boolean = false

    @ConfigField(
        label = "夜间窗口起始(时)",
        fieldType = ConfigFieldType.NUMBER,
        min = 0.0,
        max = 23.0,
        description = "夜间亮屏源使用的窗口起始小时；默认 23 点（跨午夜自动环绕）",
    )
    var nightStartHour: Int = 23

    @ConfigField(
        label = "夜间窗口结束(时)",
        fieldType = ConfigFieldType.NUMBER,
        min = 0.0,
        max = 23.0,
        description = "夜间亮屏源使用的窗口结束小时；默认次日 6 点",
    )
    var nightEndHour: Int = 6

    @ConfigField(
        label = "夜间亮屏阈值(分钟)",
        fieldType = ConfigFieldType.NUMBER,
        min = 5.0,
        max = 30.0,
        description = "窗口内持续亮屏超过该时长触发；默认 15 分钟",
    )
    var nightScreenThresholdMin: Int = 15

    @ConfigField(
        label = "连续使用触发",
        fieldType = ConfigFieldType.TOGGLE,
        description = "长时间连续用机提醒（久坐/护眼）",
    )
    var continuousUseEnabled: Boolean = false

    @ConfigField(
        label = "连续使用阈值(分钟)",
        fieldType = ConfigFieldType.NUMBER,
        min = 15.0,
        max = 120.0,
        description = "建议 45；熄屏后重新计时",
    )
    var continuousUseThresholdMin: Int = 45

    @ConfigField(
        label = "每日触发上限",
        fieldType = ConfigFieldType.NUMBER,
        min = 5.0,
        max = 1000.0,
        description = "TriggerCap：事件触发 LLM 的每日硬顶（区别于通知上限——LLM 可'决定不提醒'仍消耗 Token）。默认 100",
    )
    var dailyTriggerCap: Int = 100

    @ConfigField(
        label = "每日提醒上限",
        fieldType = ConfigFieldType.NUMBER,
        min = 1.0,
        max = 200.0,
        description = "NotifyCap：autonomous_notify 每日硬顶，超顶拒绝。默认 20",
    )
    var dailyNotifyCap: Int = 20

    /** MU5.1（音乐轨 §7.2）：AUTO 定时播放触发总开关（gate 音乐自主建单播放） */
    @ConfigField(
        label = "定时播放 AUTO 触发",
        fieldType = ConfigFieldType.TOGGLE,
        description = "允许音乐定时 AUTO 模式到点唤醒 Zero 自主建歌单播放（FIXED 直播不受此开关影响）",
    )
    var musicScheduledTriggerEnabled: Boolean = true

    /**
     * SD1（2026-09-18 用户三裁定）：自驱动回合总开关——允许 Zero 被自主唤醒行动
     * （2026-09-18 二次裁定：提醒源已收敛移除——回归纯通知）。
     * 管辖源：报点随发（selfDriveOnTick）/ next_tick 静默闹钟 / Automation SELF_DRIVE；
     * 通知白名单源走自身 notificationTriggerEnabled 开关不经此闸。
     * 回合共享主对话上下文/用户消息可打断（D68）；配额走每日触发上限。
     */
    @ConfigField(
        label = "自驱动回合",
        fieldType = ConfigFieldType.TOGGLE,
        description =
            "允许 Zero 被自主唤醒行动：报点随发/LLM 自续闹钟（next_tick）/" +
                "自驱自动化到点时在对话上下文中自主干活或自由活动——用户发消息随时打断；" +
                "频次受「每日触发上限」约束（提醒仍为纯通知不受影响）",
    )
    var selfDriveEnabled: Boolean = false

    override fun toJson(): String =
        """{"notificationTriggerEnabled":$notificationTriggerEnabled,""" +
            """"batteryFullEnabled":$batteryFullEnabled,"batteryLowEnabled":$batteryLowEnabled,""" +
            """"batteryLowThreshold":$batteryLowThreshold,""" +
            """"nightScreenEnabled":$nightScreenEnabled,""" +
            """"nightStartHour":$nightStartHour,"nightEndHour":$nightEndHour,""" +
            """"nightScreenThresholdMin":$nightScreenThresholdMin,""" +
            """"continuousUseEnabled":$continuousUseEnabled,""" +
            """"continuousUseThresholdMin":$continuousUseThresholdMin,""" +
            """"dailyTriggerCap":$dailyTriggerCap,"dailyNotifyCap":$dailyNotifyCap,""" +
            """"musicScheduledTriggerEnabled":$musicScheduledTriggerEnabled,""" +
            """"selfDriveEnabled":$selfDriveEnabled}"""

    override fun fromJson(json: String) {
        try {
            val obj = JSONObject(json)
            notificationTriggerEnabled = obj.optBoolean("notificationTriggerEnabled", true)
            batteryFullEnabled = obj.optBoolean("batteryFullEnabled", false)
            batteryLowEnabled = obj.optBoolean("batteryLowEnabled", false)
            batteryLowThreshold = obj.optInt("batteryLowThreshold", 20).coerceIn(5, 30)
            nightScreenEnabled = obj.optBoolean("nightScreenEnabled", false)
            nightStartHour = obj.optInt("nightStartHour", 23).coerceIn(0, 23)
            nightEndHour = obj.optInt("nightEndHour", 6).coerceIn(0, 23)
            nightScreenThresholdMin = obj.optInt("nightScreenThresholdMin", 15).coerceIn(5, 30)
            continuousUseEnabled = obj.optBoolean("continuousUseEnabled", false)
            continuousUseThresholdMin = obj.optInt("continuousUseThresholdMin", 45).coerceIn(15, 120)
            dailyTriggerCap = obj.optInt("dailyTriggerCap", 100).coerceIn(5, 1000)
            dailyNotifyCap = obj.optInt("dailyNotifyCap", 20).coerceIn(1, 200)
            musicScheduledTriggerEnabled = obj.optBoolean("musicScheduledTriggerEnabled", true)
            selfDriveEnabled = obj.optBoolean("selfDriveEnabled", false)
        } catch (_: Exception) {
        }
    }

    override fun defaultConfig(): ModuleConfigBase = TriggerHubConfig()
}

/**
 * 事件触发枢纽 — A3a 扩容（+设备广播四源）。
 *
 * 通知源（A2a）+ 设备状态四源（A3a）：
 *   🔋 充电满（BATTERY_CHANGED 边沿：charging→FULL 转换一次）
 *   🔋 电量低（level≤阈值，回充≥90%重armed）
 *   🌙 夜间亮屏（窗口内 SCREEN_ON 持续≥阈值，跨午夜环绕）
 *   ⏱ 连续使用（SCREEN_ON 段持续≥阈值，熄屏重置）
 *
 * D78：SCREEN_ON/OFF 禁止 Manifest 注册——动态注册挂模块生命周期（进程锚=无障碍保活 D15）。
 * 门控链：源开关→去重→TriggerCap→直调 onTrigger。
 */
@Singleton
class TriggerHub
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val systemBus: SystemBus,

        private val turns: TriggerTurnRouter,
        private val cloudEvents: CloudEventSourceClient,
        private val whitelistStore: TriggerWhitelistStore,


        private val traceManager: AgentTraceManager,

    ) : IZeroModule, com.zero.v2.core.moduleapi.ToolContributor {
        companion object {
            private const val TRACE_ID = "trigger_hub"
            private const val PREFS_CAP = "zero_trigger_cap"
            private const val KEY_DAY = "day"
            private const val KEY_COUNT = "count"
            private const val KEY_NOTIFY_DAY = "notify_day"
            private const val KEY_NOTIFY_COUNT = "notify_count"
            private const val DEDUP_WINDOW_MS = 5 * 60 * 1000L
            private const val BACKLOG_DELAY_MS = 2 * 60 * 1000L

            /** 屏幕阈值巡检周期 */
            private const val SCREEN_CHECK_INTERVAL_MS = 60_000L

            /** 云服务器名（D103 固定） */
            private const val CLOUD_SERVER = "zero-cloud"

            /** D114：云事件 SSE 端点（与 MCP 同入口/同 pin/同 token）。
             *  S7.2：HOST 动态化（TrustConfig primary）——每次连接现取，换服务器即时跟随 */
            private fun CLOUD_EVENTS_URL(): String = "https://${com.zero.v2.services.mcp.ZeroCloudTrust.HOST}:8443/events"
        }

        override val metadata =
            ModuleMetadata(
                id = "trigger_hub",
                displayName = "事件触发",
                description = "通知/充电/电量/夜间亮屏/连续使用 → 自主思考（事件驱动）",
                category = ModuleCategory.CORE,
                launchOrder = -165,
            )

        private val _lifecycle = MutableStateFlow(ModuleLifecycle.UNINITIALIZED)
        override val lifecycle = _lifecycle.asStateFlow()
        override var isEnabled: Boolean = true
        override val requiredPermissions: List<String> = emptyList()
        override val config = TriggerHubConfig()

        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

        // ═══ 通知源状态 ═══
        private val lastTriggerByPackage = HashMap<String, Long>()

        // ═══ 设备状态：屏幕段累计器 ═══
        private var screenOnAt: Long = 0 // 当前段起始（0=灭屏）
        private var continuousUseFired = false // 本段已触发连续使用
        private var nightScreenFired = false // 本段已触发夜间亮屏
        private var screenCheckJob: Job? = null // 定期巡检协程

        // ═══ 设备状态：电池 ═══
        private var wasCharging = false
        private var lowBatteryFired = false // re-arm 用

        // ═══ D114 云事件源状态 ═══
        // F18（用户裁定 2026-09-07）+ E1.2 泛化：im 游标持久化——内存态重启归零会整段重读
        // 历史，集中推送过时消息（实测 n=32 全为旧消息）；SharedPreferences 跨重启保持增量。
        // E1.2：游标语义从 epoch ts 泛化为 im_read 的 seq（transcript_events 单调序号）。
        private val imCursorPrefs by lazy {
            context.getSharedPreferences("zero_im_cursor", Context.MODE_PRIVATE)
        }

        @Volatile private var imSinceSeq: Long =
            imCursorPrefs.getLong("since_seq", 0L) // 启动即从持久层恢复

        private fun persistImCursor(seq: Long) {
            imCursorPrefs.edit().putLong("since_seq", seq).apply()
        }

        /** E29：IM 检查为事件驱动——仅 type=im 的 SSE 事件置位，tick 检查器消费。
         *  无条件每 tick 提交会与 task 事件互激成风暴（实测打爆 TriggerCap）。 */
        @Volatile private var imCheckPending = false
        private val pendingCloudTasks =
            java.util.Collections.newSetFromMap(
                java.util.concurrent.ConcurrentHashMap<String, Boolean>(),
            )
        private var cloudSourcesStarted = false

        @Volatile private var sseCall: okhttp3.Call? = null

        /** D114：SSE 专用客户端（pin 信任；readTimeout 90s = 3×心跳容差，超时即断线重连） */
        private val sseClient by lazy {
            com.zero.v2.services.mcp.ZeroCloudTrust.install(
                okhttp3.OkHttpClient.Builder()
                    .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
                    .readTimeout(90, java.util.concurrent.TimeUnit.SECONDS),
            ).build()
        }

        // ═══ 广播接收器（D78 动态注册，无 Manifest 声明）═══
        private val deviceStateReceiver =
            object : BroadcastReceiver() {
                override fun onReceive(
                    ctx: Context,
                    intent: Intent,
                ) {
                    when (intent.action) {
                        Intent.ACTION_SCREEN_ON -> onScreenOn()
                        Intent.ACTION_SCREEN_OFF -> onScreenOff()
                        Intent.ACTION_BATTERY_CHANGED -> onBatteryChanged(intent)
                    }
                }
            }

        override suspend fun onAwake() {
            _lifecycle.value = ModuleLifecycle.AWAKE
            traceManager.record(
                TRACE_ID,
                NodeType.SYSTEM_EVENT,
                metadata.id,
                "hub_awake: whitelist=${whitelistStore.packages.value.size}",
            )
            // S4.2-fix（同类修复，L3 日志实证 autonomous_notify 从未注册）：FC 注册须在
            // onAwake 自调——ModuleLifecycleManager 不统一代调 registerFunctions；
            // 漏调 = D124 降级链兜底（autonomous_notify）静默不在工具列表

        }

        override suspend fun onStart() {
            _lifecycle.value = ModuleLifecycle.STARTED
            subscribeNotifications()
            registerDeviceStateReceiver()
            scheduleBacklog()
            startCloudSources()
            startLocalEventSeam()
        }

        override suspend fun onDestroy() {
            unregisterDeviceStateReceiver()
            sseCall?.cancel()
            stopLocalEventSeam()
            turns.close()
            scope.cancel()
            _lifecycle.value = ModuleLifecycle.DESTROYED
        }

        override suspend fun onConfigChanged(newConfig: ModuleConfigBase) {}

        // ═══ A3b：autonomous_notify FC（D75 护栏 + D79 TurnContext 校验）═══

        override fun contributions(): List<com.zero.v2.core.moduleapi.ToolContribution> =
            com.zero.v2.core.moduleapi.toolDeclarations(metadata.id) {
            val functionRegistry = this
            functionRegistry.register(
                FunctionDefinition(
                    name = "autonomous_notify",
                    description =
                        "发送系统通知提醒用户（免确认直达通知栏，不会弹窗打断）。仅在自主思考回合中可用。" +
                            "提醒类用这个，不要用 push_notification（那个会弹确认窗）。",
                    parameters =
                        mapOf(
                            "type" to "object",
                            "properties" to
                                mapOf(
                                    "title" to mapOf("type" to "string", "description" to "通知标题（简短）"),
                                    "message" to mapOf("type" to "string", "description" to "通知内容（具体、可操作）"),
                                ),
                            "required" to listOf("title", "message"),
                        ),
                ),
                metadata.id,
                permission = ActionPermission.AUTO,
            ) { args ->
                val title = args["title"] as? String ?: ""
                val message = args["message"] as? String ?: ""
                sendAutonomousNotify(title, message)
            }
        }

        override suspend fun checkAvailability(): Boolean = true

        override fun markDisabled() {
            _lifecycle.value = ModuleLifecycle.DISABLED
        }

        /**
         * D75 三重护栏：① TurnContext 源校验（D79——经 bridge.activeTurn()）② 每日 NotifyCap ③ 发送。
         * 固定渠道 zero_reminder（IMPORTANCE_HIGH，SystemEventModule 同渠道先例）。
         */
        private suspend fun sendAutonomousNotify(
            title: String,
            message: String,
        ): FunctionResult {
            // ① D79 源校验：仅 AUTONOMOUS 回合可调（USER/LIVE 拒绝）
            // D1-fix（2026-09-10）：文案分句如实报告被拒原因——原单句在"source 已对但 triggerId
            // 缺失"时自相矛盾（"当前来源: AUTONOMOUS"却拒绝），误导排查方向
            val turn = turns.activeTurn()
            val rejectReason =
                when {
                    turn == null -> "无活跃回合"
                    turn.source != TurnSource.AUTONOMOUS -> "来源 ${turn.source} 非自主回合"
                    turn.triggerId == null -> "自主回合缺 triggerId（触发关联链异常——请反馈日志）"
                    else -> null
                }
            if (rejectReason != null) {
                traceManager.record(
                    TRACE_ID,
                    NodeType.SYSTEM_EVENT,
                    metadata.id,
                    "notify_rejected: not_guard_context turnSource=${turn?.source} triggerId=${turn?.triggerId?.take(8)}",
                )
                return FunctionResult.failure(
                    "autonomous_notify 仅在自主思考回合中可用（$rejectReason）",
                )
            }

            // ② D75 每日 NotifyCap
            if (!checkAndIncrementNotifyCap()) {
                traceManager.record(
                    TRACE_ID,
                    NodeType.SYSTEM_EVENT,
                    metadata.id,
                    "notify_rejected_cap",
                )
                return FunctionResult.failure("今日提醒已达上限，请明天再试")
            }

            // ③ 发送（zero_reminder 渠道，IMPORTANCE_HIGH）
            return try {
                val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
                val channelId = "zero_reminder"
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    nm.createNotificationChannel(
                        android.app.NotificationChannel(
                            channelId,
                            "Zero 自主提醒",
                            android.app.NotificationManager.IMPORTANCE_HIGH,
                        ),
                    )
                }
                val notification =
                    NotificationCompat.Builder(context, channelId)
                        .setSmallIcon(com.zero.v2.services.R.drawable.ic_stat_zero) // S8.2 品牌小图标
                        .setContentTitle(title)
                        .setContentText(message)
                        .setStyle(NotificationCompat.BigTextStyle().bigText(message))
                        .setPriority(NotificationCompat.PRIORITY_HIGH)
                        .setAutoCancel(true)
                        .build()
                nm.notify(System.currentTimeMillis().toInt(), notification)

                traceManager.record(
                    TRACE_ID,
                    NodeType.SYSTEM_EVENT,
                    metadata.id,
                    "notify_allowed: title=${title.take(30)} triggerId=${turn?.triggerId?.take(8)}",
                )
                FunctionResult.success("✅ 已发送通知提醒")
            } catch (e: Exception) {
                traceManager.record(
                    TRACE_ID,
                    NodeType.ERROR,
                    metadata.id,
                    "notify_send_failed: ${e.message?.take(40)}",
                )
                FunctionResult.failure("通知发送失败: ${e.message?.take(50)}")
            }
        }

        /** D75 每日 NotifyCap 计数（独立于 TriggerCap 的 SharedPreferences 键） */
        private fun checkAndIncrementNotifyCap(): Boolean {
            val cfg = config as TriggerHubConfig
            val prefs = context.getSharedPreferences(PREFS_CAP, Context.MODE_PRIVATE)
            val today = System.currentTimeMillis() / 86_400_000L
            val storedDay = prefs.getLong(KEY_NOTIFY_DAY, -1)
            val count = if (storedDay == today) prefs.getInt(KEY_NOTIFY_COUNT, 0) else 0
            if (count >= cfg.dailyNotifyCap) return false
            prefs.edit().putLong(KEY_NOTIFY_DAY, today).putInt(KEY_NOTIFY_COUNT, count + 1).apply()
            return true
        }

        // ═══ 通知源（A2a）═══

        private fun subscribeNotifications() {
            scope.launch {
                systemBus.onNotificationPosted.collect { event ->
                    handleNotification(event.packageName, event.appName, event.title ?: "", event.content ?: "")
                }
            }
        }

        private fun handleNotification(
            pkg: String,
            appName: String,
            title: String,
            content: String,
        ) {
            val cfg = config as TriggerHubConfig
            if (!cfg.notificationTriggerEnabled) return
            if (whitelistStore.isEmpty()) return
            if (!whitelistStore.contains(pkg)) return

            val now = System.currentTimeMillis()
            val last = lastTriggerByPackage[pkg] ?: 0
            if (now - last < DEDUP_WINDOW_MS) return
            if (!checkAndIncrementTriggerCap()) {
                traceManager.record(
                    TRACE_ID,
                    NodeType.SYSTEM_EVENT,
                    metadata.id,
                    "trigger_rejected_cap: source=NOTIFICATION",
                )
                return
            }

            lastTriggerByPackage[pkg] = now
            val payload = "[$appName] $title${if (content.isNotBlank()) ": $content" else ""}".take(200)
            traceManager.record(
                TRACE_ID,
                NodeType.SYSTEM_EVENT,
                metadata.id,
                "trigger_fired: source=NOTIFICATION pkg=$pkg",
            )
            val ev = TriggerEvent(source = "NOTIFICATION", payload = payload)
            fireTraced(ev) // S2R.4：气泡旁路退役（NOTIFICATION）
        }

        // ═══ 设备广播注册（D78 动态注册——SCREEN_ON/OFF 禁止 Manifest）═══

        private fun registerDeviceStateReceiver() {
            val filter =
                IntentFilter().apply {
                    addAction(Intent.ACTION_SCREEN_ON)
                    addAction(Intent.ACTION_SCREEN_OFF)
                    addAction(Intent.ACTION_BATTERY_CHANGED)
                }
            try {
                context.registerReceiver(deviceStateReceiver, filter)
                traceManager.record(
                    TRACE_ID,
                    NodeType.SYSTEM_EVENT,
                    metadata.id,
                    "receiver_registered: SCREEN_ON/OFF/BATTERY_CHANGED（动态注册）",
                )
            } catch (e: Exception) {
                traceManager.record(
                    TRACE_ID,
                    NodeType.ERROR,
                    metadata.id,
                    "receiver_register_failed: ${e.message?.take(40)}",
                )
            }
        }

        private fun unregisterDeviceStateReceiver() {
            try {
                context.unregisterReceiver(deviceStateReceiver)
            } catch (_: Exception) {
            }
        }

        // ═══ 屏幕段累计（A3a）═══

        private fun onScreenOn() {
            screenOnAt = System.currentTimeMillis()
            continuousUseFired = false
            nightScreenFired = false
            // 启动定期巡检（每分钟检查阈值）
            screenCheckJob =
                scope.launch {
                    while (isActive && screenOnAt > 0) {
                        delay(SCREEN_CHECK_INTERVAL_MS)
                        checkScreenThresholds()
                    }
                }
        }

        private fun onScreenOff() {
            screenCheckJob?.cancel()
            screenCheckJob = null
            screenOnAt = 0
            continuousUseFired = false
            nightScreenFired = false
        }

        /** 定期巡检：连续使用/夜间亮屏阈值检查 */
        private fun checkScreenThresholds() {
            if (screenOnAt <= 0) return
            val cfg = config as TriggerHubConfig
            val now = System.currentTimeMillis()
            val durationMin = (now - screenOnAt) / 60_000

            // 连续使用
            if (cfg.continuousUseEnabled && !continuousUseFired && durationMin >= cfg.continuousUseThresholdMin) {
                continuousUseFired = true
                if (checkAndIncrementTriggerCap()) {
                    traceManager.record(
                        TRACE_ID,
                        NodeType.SYSTEM_EVENT,
                        metadata.id,
                        "trigger_fired: source=CONTINUOUS_USE duration=${durationMin}min",
                    )
                    val ev =
                        TriggerEvent(
                            source = "CONTINUOUS_USE",
                            payload = "连续使用已 $durationMin 分钟",
                        )
                    fireTraced(ev) // S2R.4：气泡旁路退役（CONTINUOUS_USE）
                }
            }

            // 夜间亮屏（跨午夜环绕判定 B3）
            val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
            if (cfg.nightScreenEnabled && !nightScreenFired && durationMin >= cfg.nightScreenThresholdMin &&
                isNightHour(hour, cfg.nightStartHour, cfg.nightEndHour)
            ) {
                nightScreenFired = true
                if (checkAndIncrementTriggerCap()) {
                    traceManager.record(
                        TRACE_ID,
                        NodeType.SYSTEM_EVENT,
                        metadata.id,
                        "trigger_fired: source=NIGHT_SCREEN hour=$hour duration=${durationMin}min",
                    )
                    val ev =
                        TriggerEvent(
                            source = "NIGHT_SCREEN",
                            payload = "凌晨 $hour 点仍在用机，已亮屏 $durationMin 分钟",
                        )
                    fireTraced(ev) // S2R.4：气泡旁路退役（NIGHT_SCREEN）
                }
            }
        }

        /** 夜间窗口跨午夜环绕判定（B3） */
        private fun isNightHour(
            h: Int,
            start: Int,
            end: Int,
        ): Boolean = if (start <= end) h in start until end else h >= start || h < end

        // ═══ 电池源（A3a）═══

        private fun onBatteryChanged(intent: Intent) {
            val cfg = config as TriggerHubConfig
            val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
            val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
            if (level < 0 || scale <= 0) return
            val pct = level * 100 / scale

            val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING
            val isFull = status == BatteryManager.BATTERY_STATUS_FULL

            // 充电满边沿检测（D3：charging→FULL 转换一次）
            if (cfg.batteryFullEnabled && isFull && wasCharging && !isCharging) {
                if (checkAndIncrementTriggerCap()) {
                    traceManager.record(
                        TRACE_ID,
                        NodeType.SYSTEM_EVENT,
                        metadata.id,
                        "trigger_fired: source=BATTERY_FULL pct=$pct",
                    )
                    val ev = TriggerEvent(source = "BATTERY_FULL", payload = "充电已完成")
                    fireTraced(ev) // S2R.4：气泡旁路退役（BATTERY_FULL）
                }
            }
            wasCharging = isCharging || isFull

            // 电量低（回充≥90%重armed）
            if (cfg.batteryLowEnabled && !lowBatteryFired && pct <= cfg.batteryLowThreshold) {
                lowBatteryFired = true
                if (checkAndIncrementTriggerCap()) {
                    traceManager.record(
                        TRACE_ID,
                        NodeType.SYSTEM_EVENT,
                        metadata.id,
                        "trigger_fired: source=BATTERY_LOW pct=$pct",
                    )
                    val ev = TriggerEvent(source = "BATTERY_LOW", payload = "电量 $pct%，低于阈值")
                    fireTraced(ev) // S2R.4：气泡旁路退役（BATTERY_LOW）
                }
            } else if (pct >= 90) {
                lowBatteryFired = false // Re-arm
            }
        }

        // ═══ D11-R（用户裁定 2026-09-20）：通用本地模块事件缝 ═══
        // 本机 127.0.0.1:7879 · POST /event {"source":"QQ_PRIVATE","payload":"[sender] text"}
        // → 原样走 fireTraced（R6=A 打断/节奏计数/S2R 留痕全链复用）。
        // 通用缝：任何本地动态模块（QQ/Telegram/邮件…）可触发 Zero，一次建成终身复用
        // （ROADMAP_P5_P8 MessagePlatform 思想的最小化落地）。
        // 信任边界：仅绑 loopback——与 supervisor 控制口 7878 同级（模块本就以最大授权 D67
        // 运行，本缝不构成提权）；token 硬化列为后续可选项。E28：接受/拒绝全程留痕。

        @Volatile private var seamServer: java.net.ServerSocket? = null

        private fun startLocalEventSeam() {
            scope.launch(kotlinx.coroutines.Dispatchers.IO) {
                try {
                    val server = java.net.ServerSocket(7879, 8, java.net.InetAddress.getByName("127.0.0.1"))
                    seamServer = server
                    traceManager.record(
                        TRACE_ID,
                        NodeType.SYSTEM_EVENT,
                        metadata.id,
                        "local_event_seam_started: 127.0.0.1:7879",
                    )
                    while (kotlinx.coroutines.currentCoroutineContext().isActive) {
                        val client =
                            try {
                                server.accept()
                            } catch (e: Exception) {
                                break
                            }
                        launch {
                            client.use { sock ->
                                sock.soTimeout = 10_000
                                try {
                                    handleSeamRequest(sock)
                                } catch (_: Exception) {
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    traceManager.record(
                        TRACE_ID,
                        NodeType.ERROR,
                        metadata.id,
                        "local_event_seam_failed: ${e.javaClass.simpleName} ${e.message?.take(50)}",
                    )
                }
            }
        }

        private fun stopLocalEventSeam() {
            try {
                seamServer?.close()
            } catch (_: Exception) {
            }
            seamServer = null
        }

        /** 字节级 HTTP 解析（content-length 为字节数——中文载荷 3 字节/字，字符读必挂——冒烟实证修复） */
        private suspend fun handleSeamRequest(sock: java.net.Socket) {
            val input = sock.getInputStream()
            val os = sock.getOutputStream()

            fun respond(
                status: String,
                body: String,
            ) {
                val b = body.toByteArray(Charsets.UTF_8)
                os.write(
                    (
                        "HTTP/1.1 $status\r\nContent-Type: application/json\r\n" +
                            "Content-Length: ${b.size}\r\nConnection: close\r\n\r\n"
                    ).toByteArray(),
                )
                os.write(b)
                os.flush()
            }
            // 头部：逐字节读到 \r\n\r\n
            val head = java.io.ByteArrayOutputStream()
            while (true) {
                val c = input.read()
                if (c < 0) return
                head.write(c)
                val h = head.toByteArray()
                if (h.size >= 4 && h[h.size - 4] == '\r'.code.toByte() && h[h.size - 3] == '\n'.code.toByte() &&
                    h[h.size - 2] == '\r'.code.toByte() && h[h.size - 1] == '\n'.code.toByte()
                ) {
                    break
                }
                if (h.size > 8192) {
                    respond("431 Request Header Fields Too Large", "{}")
                    return
                }
            }
            val headStr = head.toString("UTF-8")
            val requestLine = headStr.substringBefore("\r\n")
            val contentLength =
                headStr.lineSequence()
                    .firstOrNull { it.lowercase().startsWith("content-length:") }
                    ?.substringAfter(':')?.trim()?.toIntOrNull() ?: 0
            if (contentLength <= 0 || contentLength > 64 * 1024) {
                respond("400 Bad Request", "{}")
                return
            }
            // 体：精确读 contentLength 字节
            val bodyBuf = ByteArray(contentLength)
            var read = 0
            while (read < contentLength) {
                val n = input.read(bodyBuf, read, contentLength - read)
                if (n < 0) break
                read += n
            }
            if (read < contentLength) {
                respond("400 Bad Request", "{}")
                return
            }
            if (!requestLine.startsWith("POST /event")) {
                respond("404 Not Found", "{}")
                return
            }
            var triggered = false
            try {
                val obj = org.json.JSONObject(String(bodyBuf, 0, read, Charsets.UTF_8))
                val source = obj.optString("source").trim().uppercase()
                val payload = obj.optString("payload").take(4000)
                // source 形约束：事件源风格大写下划线（QQ_PRIVATE/QQ_GROUP/…），防垃圾注入
                if (!Regex("^[A-Z][A-Z0-9_]{2,39}$").matches(source) || payload.isBlank()) {
                    traceManager.record(
                        TRACE_ID,
                        NodeType.SYSTEM_EVENT,
                        metadata.id,
                        "local_event_rejected: source=${source.take(20)} invalid",
                    )
                } else if (checkAndIncrementTriggerCap()) {
                    triggered = true
                    traceManager.record(
                        TRACE_ID,
                        NodeType.SYSTEM_EVENT,
                        metadata.id,
                        "local_event_accepted: source=$source len=${payload.length}",
                    )
                    // 与既有源同构：fire 独立协程（E30），全链复用（R6=A/节奏/留痕）
                    scope.launch {
                        try {
                            fireTraced(TriggerEvent(source = source, payload = payload))
                        } catch (e: Exception) {
                            traceManager.record(
                                TRACE_ID,
                                NodeType.ERROR,
                                metadata.id,
                                "local_event_fire_failed: ${e.javaClass.simpleName}",
                            )
                        }
                    }
                } else {
                    traceManager.record(
                        TRACE_ID,
                        NodeType.SYSTEM_EVENT,
                        metadata.id,
                        "local_event_capped: source=$source",
                    )
                }
            } catch (e: Exception) {
                traceManager.record(
                    TRACE_ID,
                    NodeType.ERROR,
                    metadata.id,
                    "local_event_parse_failed: ${e.message?.take(40)}",
                )
            }
            respond("200 OK", """{"ok":true,"triggered":$triggered}""")
        }

        // ═══ 每日 TriggerCap（D80）═══

        /**
         * MU5.1（C3 裁决 · 设计 §7.2）：音乐 AUTO 定时触发**公开**入口——fireTraced 私有，
         * MusicModule（app）不可直调。双门控第二道：`musicScheduledTriggerEnabled` 开关 +
         * `dailyTriggerCap` 全局配额（BATTERY 源同模式 fire 前检查）——音乐 AUTO 同样计数
         * 受顶不可绕（第一道音乐域 scheduledAutoCap 在 MusicModule 内）。
         * @return true=已触发；false=被开关/全局日顶拒绝（trace 已留，状态由调用方决策）
         */
        fun fireMusicSchedule(
            payload: String,
            hint: String?,
        ): Boolean {
            val cfg = config as TriggerHubConfig
            if (!cfg.musicScheduledTriggerEnabled) {
                traceManager.record(
                    TRACE_ID,
                    NodeType.SYSTEM_EVENT,
                    metadata.id,
                    "music_auto_disabled: 开关关闭，拒绝触发",
                )
                return false
            }
            if (!checkAndIncrementTriggerCap()) {
                traceManager.record(
                    TRACE_ID,
                    NodeType.SYSTEM_EVENT,
                    metadata.id,
                    "music_auto_blocked_global_cap: 全局日顶已满，拒绝触发",
                )
                return false
            }
            traceManager.record(
                TRACE_ID,
                NodeType.SYSTEM_EVENT,
                metadata.id,
                "trigger_fired: source=SCHEDULED_PLAYBACK hint=${hint?.take(30)}",
            )
            val ev = TriggerEvent(source = "SCHEDULED_PLAYBACK", payload = payload)
            fireTraced(ev) // S2R.4：气泡旁路退役（SCHEDULED_PLAYBACK）
            return true
        }

        /** S2R.4：统一 fire——仅业务链 onTrigger（显示旁路已退役 D-P7；可见性归消息本体+留痕） */
        private fun fireTraced(trigger: TriggerEvent) {
            turns.onTrigger(trigger)
        }

        /**
         * SD1：自驱动回合公开入口（报点随发 / next_tick 静默闹钟 / Automation SELF_DRIVE 共用；
         * 2026-09-18 二次裁定：提醒源已移除——回归纯通知）。
         * 双门控：`selfDriveEnabled` 开关 + `dailyTriggerCap` 全局日顶（用户裁定②：复用每日
         * 预算不做额外限制）。payload 非空=带任务语境；空=自由活动回合。
         * @return true=已触发；false=开关关/日顶满（trace 已留）
         */
        fun fireSelfDrive(
            payload: String,
            reason: String,
        ): Boolean {
            val cfg = config as TriggerHubConfig
            if (!cfg.selfDriveEnabled) return false
            if (!checkAndIncrementTriggerCap()) {
                traceManager.record(
                    TRACE_ID,
                    NodeType.SYSTEM_EVENT,
                    metadata.id,
                    "self_drive_blocked_global_cap: 全局日顶已满，拒绝触发 reason=$reason",
                )
                return false
            }
            traceManager.record(
                TRACE_ID,
                NodeType.SYSTEM_EVENT,
                metadata.id,
                "trigger_fired: source=SELF_DRIVE reason=$reason payload=${payload.take(40)}",
            )
            val ev = TriggerEvent(source = "SELF_DRIVE", payload = payload)
            fireTraced(ev) // S2R.4：气泡旁路退役（SELF_DRIVE）
            return true
        }

        // ═══ S2.2/G14/C11：D124 送达通道状态（wechat_status 探测，5min 内存缓存）═══
        // S8.3（图纸 D4 · 裁定 10）：缓存结构化（Boolean?=登录态，null=探测失败）——
        // digest（prompt 用）与权限中心「通道状态」区块共用同一探测与缓存

        @Volatile private var channelStatusCache: Pair<Long, Boolean?>? = null

        /** 原始探测（wechat_status.logged_in；null=未知/失败）——C11 5min 缓存 */
        private suspend fun wechatLoggedInCached(): Boolean? {
            val cached = channelStatusCache
            if (cached != null && System.currentTimeMillis() - cached.first < 300_000) {
                return cached.second
            }
            val logged =
                try {
                    val st = cloudEvents.callToolForServer(CLOUD_SERVER, "wechat_status", emptyMap())
                    st?.optBoolean("logged_in")
                } catch (e: Exception) {
                    traceManager.record(
                        TRACE_ID,
                        NodeType.SYSTEM_EVENT,
                        metadata.id,
                        "channel_status_probe_failed: ${e.message?.take(40)}",
                    )
                    null
                }
            channelStatusCache = System.currentTimeMillis() to logged
            return logged
        }

        private suspend fun channelStatusDigest(): String {
            val logged = wechatLoggedInCached()
            return "微信:" +
                when (logged) {
                    true -> "已登录"
                    false -> "未登录"
                    null -> "未知"
                }
        }

        /** S8.3：权限中心通道状态区块数据源（与 D124 prompt 探测同缓存） */
        suspend fun wechatChannelStatus(): Boolean? = wechatLoggedInCached()

        private val triggerQuota by lazy {
            val prefs = context.getSharedPreferences(PREFS_CAP, Context.MODE_PRIVATE)
            DailyTriggerQuota(
                read = { prefs.getLong(KEY_DAY, -1) to prefs.getInt(KEY_COUNT, 0) },
                write = { day, count -> prefs.edit().putLong(KEY_DAY, day).putInt(KEY_COUNT, count).apply() },
            )
        }

        private fun checkAndIncrementTriggerCap(): Boolean = triggerQuota.acquire(
            System.currentTimeMillis() / 86_400_000L,
            (config as TriggerHubConfig).dailyTriggerCap,
        )

        // ═══ 启动补检（D73 BACKLOG）═══

        private fun scheduleBacklog() {
            scope.launch {
                delay(BACKLOG_DELAY_MS)
                val cfg = config as TriggerHubConfig
                if (!cfg.notificationTriggerEnabled) return@launch
                traceManager.record(
                    TRACE_ID,
                    NodeType.SYSTEM_EVENT,
                    metadata.id,
                    "trigger_fired: source=BACKLOG",
                )
                val ev =
                    TriggerEvent(
                        source = "BACKLOG",
                        payload = "[启动补检] Zero 刚重新启动。请检查最近通知/日程是否有需处理事项。",
                    )
                fireTraced(ev) // S2R.4：气泡旁路退役（BACKLOG）
            }
        }

        // ═══ D114：云事件源（SSE → kick；onTick → 检查器；tick 兜底）═══

        private fun startCloudSources() {
            if (cloudSourcesStarted) return
            cloudSourcesStarted = true
            // 异步云任务提交 → 登记待感知（E29：过滤自产 wechat_read——其任务进感知集即风暴）
            cloudEvents.onAsyncTaskSubmitted = { tid, toolName ->
                if (toolName != "wechat_read") pendingCloudTasks.add(tid)
            }
            // onTick 云检查器（kick 提前唤醒 tick；断线期间 tick 自适应兜底）
            scope.launch {
                systemBus.onTick.collect {
                    try {
                        cloudTickCheck()
                    } catch (e: Exception) {
                        traceManager.record(
                            TRACE_ID,
                            NodeType.ERROR,
                            metadata.id,
                            "cloud_tick_check_failed: ${e.message?.take(40)}",
                        )
                    }
                }
            }
            // SSE 常驻订阅（独立协程，断线指数退避）
            scope.launch { sseLoop() }
        }

        /** tick 消费：IM 增量（仅事件驱动）+ 待感知任务状态（Z33 铁律——失败必留痕）
         *  E30 防御：fire 段与 collect 上下文解耦（scope.launch）+ 全程逐行 trace。
         *  E1.2 泛化：wechat_read（异步任务+三探轮询）→ im_read（同步直调——im_read 已是
         *  服务端同步工具，无 task_id/cloud_task_status 中间层）；通道取自消息字段
         *  （WECHAT_PRIVATE/QQ_PRIVATE/WECOM_PRIVATE，与 S2R systemSource 统一）。 */
        private suspend fun cloudTickCheck() {
            // ① IM 增量——仅 imCheckPending（SSE type=im 事件置位）时提交，杜绝自激励（E29）
            if (imCheckPending) {
                imCheckPending = false
                traceManager.record(
                    TRACE_ID,
                    NodeType.SYSTEM_EVENT,
                    metadata.id,
                    "im_check_start: since=$imSinceSeq",
                )
                val sub =
                    cloudEvents.callToolForServer(
                        CLOUD_SERVER,
                        "im_read",
                        mapOf("since" to imSinceSeq),
                    )
                if (sub == null) {
                    traceManager.record(
                        TRACE_ID,
                        NodeType.SYSTEM_EVENT,
                        metadata.id,
                        "im_check_skipped: im_read 不可达（服务器/MCP 未连？）",
                    )
                    return
                }
                // 同步直调：messages/next_cursor 即第一层字段（无 cloud_task_status 双层包装）
                val msgs = sub.optJSONArray("messages")
                val n = msgs?.length() ?: 0
                traceManager.record(
                    TRACE_ID,
                    NodeType.SYSTEM_EVENT,
                    metadata.id,
                    "im_result: msgs=$n",
                )
                imSinceSeq = sub.optLong("next_cursor", imSinceSeq)
                persistImCursor(imSinceSeq) // F18：游标跨重启持久（防重启整段重读）
                if (n > 0) {
                    // E30：fire 段独立协程——与 collect 上下文解耦，免疫其取消/吞噬
                    scope.launch {
                        try {
                            // F18（用户裁定）：实时聊天仅处理最新一条——批量多为离线期积压的
                            // 过时消息，最新一条代表用户当前意图（跨通道取 ts 最新）
                            var latest: org.json.JSONObject? = null
                            var latestTs = -1.0
                            for (i in 0 until n) {
                                val m = msgs!!.optJSONObject(i) ?: continue
                                val ts = m.optDouble("ts", -1.0)
                                if (ts >= latestTs) {
                                    latestTs = ts
                                    latest = m
                                }
                            }
                            val m = latest ?: return@launch
                            val ch = m.optString("channel", "WECHAT_PRIVATE")
                            // E1.2：sender 全量保留（im_send 的 peer 必须是完整会话 ID——
                            // 截断后 LLM 无法回投；显示语义由指令层解释）
                            val line =
                                "[${m.optString("sender")}] " +
                                    m.optString("text").take(200)
                            if (checkAndIncrementTriggerCap()) {
                                traceManager.record(
                                    TRACE_ID,
                                    NodeType.SYSTEM_EVENT,
                                    metadata.id,
                                    "trigger_fired: source=$ch n=$n latest_only=true",
                                )
                                // S2.2：F7 triggerId 贯通 + D124 通道状态附 payload（G14/C11）
                                val digest = channelStatusDigest()
                                val ev =
                                    TriggerEvent(
                                        source = ch,
                                        payload = line + "\n[送达通道] $digest",
                                    )
                                fireTraced(ev)
                            } else {
                                traceManager.record(
                                    TRACE_ID,
                                    NodeType.SYSTEM_EVENT,
                                    metadata.id,
                                    "im_fire_rejected_cap",
                                )
                            }
                        } catch (e: Exception) {
                            traceManager.record(
                                TRACE_ID,
                                NodeType.ERROR,
                                metadata.id,
                                "im_fire_failed: ${e.javaClass.simpleName} ${e.message?.take(50)}",
                            )
                        }
                    }
                }
            }
            // ② 待感知任务
            if (pendingCloudTasks.isEmpty()) return
            for (tid in pendingCloudTasks.toList()) {
                val st =
                    cloudEvents.callToolForServer(
                        CLOUD_SERVER,
                        "cloud_task_status",
                        mapOf("task_id" to tid),
                    )
                if (st == null) {
                    traceManager.record(
                        TRACE_ID,
                        NodeType.SYSTEM_EVENT,
                        metadata.id,
                        "task_check_skipped: tid=${tid.take(8)}（下 tick 再查）",
                    )
                    continue
                }
                val status = st.optString("status")
                if (status == "done" || status == "failed") {
                    pendingCloudTasks.remove(tid)
                    if (!checkAndIncrementTriggerCap()) continue
                    val type = st.optString("type", "task")
                    traceManager.record(
                        TRACE_ID,
                        NodeType.SYSTEM_EVENT,
                        metadata.id,
                        "trigger_fired: source=CLOUD_TASK type=$type status=$status",
                    )
                    // S2.2：F7 + D124 同上
                    val digest = channelStatusDigest()
                    val ev =
                        TriggerEvent(
                            source = "CLOUD_TASK",
                            payload = "云端任务 $type 已$status（task_id=${tid.take(8)}）\n[送达通道] $digest",
                        )
                    fireTraced(ev) // S2R.4：气泡旁路退役（CLOUD_TASK）
                }
            }
        }

        /**
         * D114：SSE 常驻订阅——手写行解析（EventSource 在独立 okhttp3-sse 包，不引依赖）。
         * 事件 wechat/task → turns.kick()（D19 官方语义：打断等待提前发 tick）。
         * 断线指数退避重连（1s×2 封顶 5min，Z29）。
         */
        private suspend fun sseLoop() {
            var backoffMs = 1000L
            while (kotlinx.coroutines.currentCoroutineContext().isActive) {
                val token =
                    try {
                        cloudEvents.credential()
                    } catch (_: Exception) {
                        null
                    }
                if (token.isNullOrBlank()) {
                    traceManager.record(
                        TRACE_ID,
                        NodeType.SYSTEM_EVENT,
                        metadata.id,
                        "sse_skipped: 无 zero-cloud token（未配置服务器）",
                    )
                    delay(60_000L)
                    continue
                }
                try {
                    val call =
                        sseClient.newCall(
                            okhttp3.Request.Builder()
                                .url("${CLOUD_EVENTS_URL()}?token=$token")
                                .header("Accept", "text/event-stream")
                                .build(),
                        )
                    sseCall = call
                    call.execute().use { resp ->
                        if (!resp.isSuccessful) {
                            traceManager.record(
                                TRACE_ID,
                                NodeType.SYSTEM_EVENT,
                                metadata.id,
                                "sse_http_${resp.code}（${resp.message.take(20)}）",
                            )
                            throw java.io.IOException("HTTP ${resp.code}")
                        }
                        traceManager.record(
                            TRACE_ID,
                            NodeType.SYSTEM_EVENT,
                            metadata.id,
                            "sse_connected: ${CLOUD_EVENTS_URL()}",
                        )
                        backoffMs = 1000L // 连接成功即重置退避
                        val reader =
                            java.io.BufferedReader(
                                java.io.InputStreamReader(resp.body!!.byteStream(), Charsets.UTF_8),
                            )
                        while (true) {
                            val line = reader.readLine() ?: break // 流终=断线
                            if (!line.startsWith("data: ")) continue
                            val type =
                                try {
                                    JSONObject(line.removePrefix("data: ")).optString("type")
                                } catch (_: Exception) {
                                    ""
                                }
                            // E1.2-解耦（2026-09-20 用户裁定 a+b）：任意云事件类型即 kick——
                            // 云服新增事件类型（如 type=file）零手机发版。检查器自行决定查什么
                            // （im_read 游标幂等，无新消息空返；E29 不自激励——纯读取不发事件）。
                            // type 仅留痕可观测（E28）。
                            if (type.isNotBlank()) {
                                imCheckPending = true
                                traceManager.record(
                                    TRACE_ID,
                                    NodeType.SYSTEM_EVENT,
                                    metadata.id,
                                    "sse_event: type=$type → kick",
                                )
                                turns.kick()
                            }
                        }
                    }
                } catch (e: Exception) {
                    // 断线（readTimeout 90s / 网络切换 / 服务器重启）→ 退避重连；tick 兜底覆盖窗口
                    traceManager.record(
                        TRACE_ID,
                        NodeType.SYSTEM_EVENT,
                        metadata.id,
                        "sse_disconnected: ${e.javaClass.simpleName} 重连@${backoffMs}ms",
                    )
                } finally {
                    sseCall = null
                }
                delay(backoffMs)
                backoffMs = minOf(backoffMs * 2, 300_000L)
            }
        }
    }
