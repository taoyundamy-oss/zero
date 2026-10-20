package com.zero.v2.app.assistant

import android.content.Context
import android.os.BatteryManager
import com.zero.v2.core.moduleapi.ConfigField
import com.zero.v2.core.moduleapi.ConfigFieldType
import com.zero.v2.core.moduleapi.IZeroModule
import com.zero.v2.core.moduleapi.ModuleCategory
import com.zero.v2.core.moduleapi.ModuleConfigBase
import com.zero.v2.core.moduleapi.ModuleLifecycle
import com.zero.v2.core.moduleapi.ModuleMetadata
import com.zero.v2.core.trace.AgentTraceManager
import com.zero.v2.core.trace.NodeType
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Assistant 模块配置 — KB3.2（设计 §18.3 · 裁-B/裁-C）。
 *
 * 唤醒词是**用户偏好非约定常数**，入 ConfigField(TEXT)（与音乐 MD15 目录常量律相反
 * 方向、合规，KD13）；KWS 拼音序列随 KB3.3/3.4 接入（默认词硬预置零依赖，AB-B）。
 */
class AssistantConfig : ModuleConfigBase() {

    @ConfigField(label = "语音唤醒", fieldType = ConfigFieldType.TOGGLE,
        description = "监听唤醒词（见下方字段）唤起语音会话。亮屏/前台期间有效——息屏不可达系平台特权墙（仅预装助手可得），非功能缺陷")
    var hotwordEnabled: Boolean = false

    @ConfigField(label = "常驻策略", fieldType = ConfigFieldType.DROPDOWN,
        options = ["关", "仅充电", "全时段"],
        description = "监听策略（非后台启动保证）：关=不监听；仅充电=插电时监听（省电）；全时段=通知常驻。受 Android 后台启动限制，后台触发失败会留痕并在下次合法窗口补启动")
    var residentMode: String = "关"

    @ConfigField(label = "唤醒词", fieldType = ConfigFieldType.TEXT,
        description = "喊出即唤起语音会话。默认「你好大帝」（L3 实测去逗号与模型词表一致）；自定义编辑能力随 KB3.4 开放")
    var wakeWord: String = "你好大帝"

    /** wakeWord 为自由文本（可能含引号）——整体经 JSONObject 构建防注入坏 JSON */
    override fun toJson(): String = JSONObject().apply {
        put("hotwordEnabled", hotwordEnabled)
        put("residentMode", residentMode)
        put("wakeWord", wakeWord)
    }.toString()

    override fun fromJson(json: String) {
        try {
            val obj = JSONObject(json)
            hotwordEnabled = obj.optBoolean("hotwordEnabled", false)
            val mode = obj.optString("residentMode", "关")
            residentMode = if (mode in RESIDENT_MODES) mode else "关"
            wakeWord = obj.optString("wakeWord", "你好大帝").trim().ifEmpty { "你好大帝" }
        } catch (_: Exception) {}
    }

    override fun defaultConfig(): ModuleConfigBase = AssistantConfig()

    companion object {
        val RESIDENT_MODES = listOf("关", "仅充电", "全时段")
        /** KD15/AA 启动来源规则——residentMode 语义 = 监听策略而非后台启动保证 */
        const val MODE_OFF = "关"
        const val MODE_CHARGING_ONLY = "仅充电"
        const val MODE_ALWAYS = "全时段"
    }
}

/**
 * Assistant 模块 — KB3.2 配置骨架（设计 §18.2 · KD11/KD15）。
 *
 * 职责：承载 AssistantConfig（模块详情页 AutoForm 渲染，零 UI 代码）+ **HotwordService
 * 生命周期编排位**（onConfigChanged → 按策略启停——服务类随 KB3.3 落地，本步编排位为
 * 空实现并留注释锚点）。无 FC（Assistant 域只读配置）；手势/电源键入口已由
 * ZeroAssistantService/AssistHandlerActivity（KB3.1）承担。
 */
@Singleton
class AssistantModule @Inject constructor(
    @ApplicationContext private val appContext: Context,
    /** KB3.4-fix5：App 前台窗口信号（合法 FGS 启动窗口补拉） */
    private val systemBus: com.zero.v2.core.bus.SystemBus,
    private val traceManager: AgentTraceManager
) : IZeroModule {

    companion object {
        private const val TRACE_ID = "assistant_module"
        /** 前台热重启节流窗（服务重建含模型加载 ~1s，防 onAppForeground 连发） */
        private const val HOT_RESTART_THROTTLE_MS = 30_000L
    }

    private var lastHotRestartAt = 0L

    override val metadata = ModuleMetadata(
        id = "assistant",
        displayName = "语音助手",
        description = "系统数字助手入口（手势/电源键唤起语音会话）与唤醒词常驻监听（「你好，大帝」，亮屏有效——随 KB3.3 开放）",
        category = ModuleCategory.SYSTEM,
        launchOrder = 140
    )

    private val _lifecycle = MutableStateFlow(ModuleLifecycle.UNINITIALIZED)
    override val lifecycle = _lifecycle.asStateFlow()
    override var isEnabled: Boolean = true
    override val requiredPermissions: List<String> = emptyList()
    override val config = AssistantConfig()

    private val moduleScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override suspend fun onAwake() {
        _lifecycle.value = ModuleLifecycle.AWAKE

    }

    override suspend fun onStart() {
        _lifecycle.value = ModuleLifecycle.STARTED
        // KB3.3 接入点：按 hotwordEnabled + residentMode 编排 HotwordService
        // （KD15 启动来源规则：前台交互主路径 / 后台事件仅 desired state+catch+trace）
        applyHotwordPolicy(trigger = "start")
        // KB3.4-fix5（L3 实测）：**KD15 合法窗口信号接线**——App 回前台 = 合法 FGS 启动
        // 窗口（用户可见交互），补拉此前被后台限制/权限未授拒绝的期望态——主流第三方
        // assistant（ChatGPT 等）的 onResume 保活同款形态；设计 KD15 原文合法窗口即
        // "用户打开 app / assistant 触发"，本处为接线实现非设计变更
        moduleScope.launch {
            systemBus.onAppForeground.collect {
                applyHotwordPolicy(trigger = "app_foreground")
            }
        }
    }

    override suspend fun onDestroy() {
        moduleScope.cancel()
        _lifecycle.value = ModuleLifecycle.DESTROYED
    }

    override suspend fun onConfigChanged(newConfig: ModuleConfigBase) {
        traceManager.record(TRACE_ID, NodeType.SYSTEM_EVENT, metadata.id,
            "config_changed: hotword=${(config as AssistantConfig).hotwordEnabled} " +
                    "mode=${(config as AssistantConfig).residentMode}")
        applyHotwordPolicy(trigger = "config")
    }

    override suspend fun checkAvailability(): Boolean = true
    override fun markDisabled() { _lifecycle.value = ModuleLifecycle.DISABLED }

    /** 本模块无 FC */


    /**
     * KB3.4 编排实装：按 hotwordEnabled × residentMode 计算期望态并 try 启停。
     * **KD15 启动来源规则**：onConfigChanged 由设置页（前台）触发=合法主路径；onStart
     * 可能在后台——try 启动失败（ForegroundServiceStartNotAllowedException，Android 12+）
     * 即 trace 留痕等待合法窗口（POWER 广播/用户下次打开 app），不做保活重试。
     */
    private fun applyHotwordPolicy(trigger: String) {
        val cfg = config as AssistantConfig
        val desired = cfg.hotwordEnabled && cfg.residentMode != AssistantConfig.MODE_OFF
        moduleScope.launch {
            if (!desired) {
                HotwordService.stop(appContext)
                traceManager.record(TRACE_ID, NodeType.SYSTEM_EVENT, metadata.id,
                    "policy_stop: trigger=$trigger（期望态关闭）")
                return@launch
            }
            val canStartNow = when (cfg.residentMode) {
                AssistantConfig.MODE_ALWAYS -> true
                AssistantConfig.MODE_CHARGING_ONLY -> isCharging()
                else -> false
            }
            if (!canStartNow) {
                traceManager.record(TRACE_ID, NodeType.SYSTEM_EVENT, metadata.id,
                    "policy_wait: trigger=$trigger 期望开但仅充电档未充电（插电由 PowerReceiver 拉起）")
                return@launch
            }
            // KB3.4-fix6（L3 实测）：**前台热重启**——Android 14 后台启动的 microphone FGS
            // 被 while-in-use 静默（read 静音帧不报错），对已运行服务重发 onStartCommand 不
            // 重建进程、静默无法自愈；回前台/合法窗口时 stop→延迟→start 重建（获取麦克风
            // 前台性）。30s 节流防连发重建。
            val alreadyRunning = HotwordService.running
            val now = System.currentTimeMillis()
            if (alreadyRunning && now - lastHotRestartAt < HOT_RESTART_THROTTLE_MS) {
                return@launch  // 节流窗内（服务在跑，本窗口已重建过或刚启动）
            }
            if (alreadyRunning) {
                HotwordService.stop(appContext)
                kotlinx.coroutines.delay(600)  // 等 onDestroy 完成（stop 后立发 start 会被取消不重建）
                lastHotRestartAt = System.currentTimeMillis()
            }
            val ok = HotwordService.start(appContext)
            traceManager.record(TRACE_ID, NodeType.SYSTEM_EVENT, metadata.id,
                "policy_${if (alreadyRunning) "restart" else "start"}: trigger=$trigger ok=$ok" +
                        if (!ok) "（后台启动受限，等待合法窗口——KD15）" else "")
        }
    }

    private fun isCharging(): Boolean {
        val bm = appContext.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
        return bm?.isCharging == true
    }
}
