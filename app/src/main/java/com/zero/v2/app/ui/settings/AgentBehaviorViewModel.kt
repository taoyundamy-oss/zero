package com.zero.v2.app.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zero.v2.core.common.TokenUsage
import com.zero.v2.core.data.store.ModuleConfigStore
import com.zero.v2.core.modulemanager.ModuleLifecycleManager
import com.zero.v2.core.trace.AgentTraceManager
import com.zero.v2.core.trace.NodeType
import com.zero.v2.framework.chat.ChatBotModule
import com.zero.v2.framework.memory.MemoryModule
import com.zero.v2.services.agent.AgentRuntime
import com.zero.v2.services.agent.AgentRuntimeConfig
import com.zero.v2.framework.memory.MemoryModuleConfig
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONObject
import javax.inject.Inject
import dagger.hilt.android.qualifiers.ApplicationContext
import android.content.Context
import com.zero.v2.app.background.AutonomousKeepAliveService

/**
 * Agent 自主行为设置状态。
 * mode: OFF（停止 tick 循环，Agent 完全被动）/ SMART（默认）/ ALWAYS_ON（PowerProfile 接入后区分，P6+）
 */
data class AgentBehaviorState(
    val behaviorLoaded: Boolean = false,
    val behaviorSaving: Boolean = false,
    val behaviorError: String? = null,
    val parameterSaving: Boolean = false,
    val parameterError: String? = null,
    val parameterMessage: String? = null,
    val mode: String = "SMART",
    // SD2：报点随发自驱（AgentRuntime tick 检查此开关——"始终在线自主行动"）
    val selfDriveOnTick: Boolean = false,
    // 报点参数（直连 AgentRuntimeConfig，实时生效）
    val baseIntervalSec: Int = 90,
    val randomOffsetSec: Int = 30,
    val intervalMultiplier: Float = 3.0f,
    val maxDoublings: Int = 4,
    // S2R.8：在场静默保险窗（默认 0=禁用——裁定⑥）
    val userPresenceWindowMin: Int = 0,
    // 记忆自主行为（直连 MemoryModuleConfig）
    val enableReflection: Boolean = false,
    val reflectionHour: Int = 2,
    val graphExtractionThreshold: Int = 50,
    // 成本：会话真实统计 + 预算存储值（强制执行随 Kernel 预算层接入）
    val sessionTokens: TokenUsage = TokenUsage(),
    // N4.2（§八实测订正）：V4 Flash ¥0.1/M 输入、轻量日实测 8M——50000 半小时触顶破 UX；
    // 日预算定位"失控熔断"（百万级），月费才是真实成本控制
    val dailyTokenLimit: Long = 20_000_000,
    val monthlyCostLimitUsd: Float = 10.0f
)

/** 行为设置持久化键（ModuleConfigStore 复用，D8 迁移链兼容） */
const val AGENT_BEHAVIOR_STORE_KEY = "agent_behavior"

/**
 * Agent 自主行为 ViewModel — Step 5.1（DESIGN §1.3 用户主权）。
 * 报点/记忆参数直连各模块 config 实例（实时生效）+ ModuleConfigStore 持久化。
 */
@HiltViewModel
class AgentBehaviorViewModel internal constructor(
    private val reportConfig: AgentRuntimeConfig,
    private val memoryConfig: MemoryModuleConfig,
    private val usage: StateFlow<TokenUsage>,
    private val moduleConfigStore: ModuleConfigStore,
    private val traceManager: AgentTraceManager,
    private val applyMode: suspend (String) -> Unit,
) : ViewModel() {
    @Inject constructor(agentRuntime: AgentRuntime, memoryModule: MemoryModule, chatBot: ChatBotModule,
        moduleConfigStore: ModuleConfigStore, traceManager: AgentTraceManager, lifecycle: ModuleLifecycleManager,
        @ApplicationContext context: Context) : this(
        agentRuntime.config, memoryModule.config, chatBot.totalUsage, moduleConfigStore, traceManager,
        { mode -> checkNotNull(lifecycle.safeCall(agentRuntime, "ui_agent_mode") {
            if (mode == "OFF") agentRuntime.stopLoop() else agentRuntime.startLoop()
        }) { "运行时暂不可用" }
            if (mode == "OFF") AutonomousKeepAliveService.stop(context) else AutonomousKeepAliveService.start(context)
        },
    )

    private val _state = MutableStateFlow(AgentBehaviorState())
    val state: StateFlow<AgentBehaviorState> = _state.asStateFlow()
    private var retryBehavior: Pair<AgentBehaviorState, Boolean>? = null
    private var retryParameter: (() -> Unit)? = null

    init {
        viewModelScope.launch {
            loadPersisted()
            syncFromConfigs()
        }
        // 会话 Token 实时统计
        viewModelScope.launch {
            usage.collect { usage ->
                _state.update { it.copy(sessionTokens = usage) }
            }
        }
    }

    private suspend fun loadPersisted() {
        try {
            moduleConfigStore.get(AGENT_BEHAVIOR_STORE_KEY)?.let { json ->
                val obj = JSONObject(json)
                _state.update {
                    it.copy(
                        mode = obj.optString("mode", "SMART"),
                        selfDriveOnTick = obj.optBoolean("selfDriveOnTick", false),
                        // N4.2 钳位兜底（§八：日 [8M,100M] / 月 [$5,$50]——存量 50000/$5 提至安全下限）
                        dailyTokenLimit = obj.optLong("dailyTokenLimit", 20_000_000).coerceIn(8_000_000L, 100_000_000L),
                        monthlyCostLimitUsd = obj.optDouble("monthlyCostLimitUsd", 10.0).toFloat().coerceIn(5.0f, 50.0f)
                    )
                }
            }
            _state.update { it.copy(behaviorLoaded = true, behaviorError = null) }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            _state.update { it.copy(behaviorLoaded = false, behaviorError = "读取自主行为设置失败，请重试") }
        }
    }

    fun reloadBehavior() {
        if (_state.value.behaviorLoaded || _state.value.behaviorSaving) return
        _state.update { it.copy(behaviorSaving = true) }
        viewModelScope.launch {
            try { loadPersisted() }
            finally { _state.update { it.copy(behaviorSaving = false) } }
        }
    }

    private fun syncFromConfigs() {
        val arc = reportConfig
        val mem = memoryConfig
        _state.update {
            it.copy(
                baseIntervalSec = arc.baseIntervalSec,
                randomOffsetSec = arc.randomOffsetSec,
                intervalMultiplier = arc.intervalMultiplier,
                maxDoublings = arc.maxDoublings,
                userPresenceWindowMin = arc.userPresenceWindowMin,
                enableReflection = mem.enableReflection,
                reflectionHour = mem.reflectionHour,
                graphExtractionThreshold = mem.graphExtractionThreshold
            )
        }
    }

    /** 模式切换 — OFF 实际停止 tick 循环；恢复模式时尝试重启 */
    fun setMode(mode: String) {
        if (mode !in setOf("OFF", "SMART", "ALWAYS_ON")) return
        if (mode == _state.value.mode && _state.value.behaviorError == null) return
        saveBehavior(_state.value.copy(mode = mode), switchMode = true)
    }

    /** Confirm the local intent before runtime reads it; rejected changes never enter retry queues. */
    private fun saveBehavior(candidate: AgentBehaviorState, switchMode: Boolean = false) {
        if (!_state.value.behaviorLoaded || _state.value.behaviorSaving) return
        retryBehavior = candidate to switchMode
        _state.update { it.copy(behaviorSaving = true, behaviorError = null) }
        viewModelScope.launch {
            var committed = false
            try {
                currentCoroutineContext().ensureActive()
                withContext(NonCancellable) {
                    moduleConfigStore.saveConfirmed(AGENT_BEHAVIOR_STORE_KEY, behaviorJson(candidate))
                    committed = true
                    _state.update { it.copy(mode = candidate.mode, selfDriveOnTick = candidate.selfDriveOnTick,
                        dailyTokenLimit = candidate.dailyTokenLimit, monthlyCostLimitUsd = candidate.monthlyCostLimitUsd) }
                    if (switchMode) applyMode(candidate.mode)
                }
                retryBehavior = null
                currentCoroutineContext().ensureActive()
                traceManager.record("ui_agent_behavior", NodeType.CONFIG_CHANGE, "settings", "behavior_saved")
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                _state.update { it.copy(behaviorError = if (committed) "设置已保存，运行切换失败，请重试" else "保存自主行为设置失败，请重试") }
            } finally {
                _state.update { it.copy(behaviorSaving = false) }
            }
        }
    }

    fun retryBehaviorSave() {
        retryBehavior?.let { (candidate, switchMode) -> saveBehavior(candidate, switchMode) }
    }

    private fun behaviorJson(s: AgentBehaviorState): String = JSONObject()
        .put("mode", s.mode).put("selfDriveOnTick", s.selfDriveOnTick)
        .put("dailyTokenLimit", s.dailyTokenLimit).put("monthlyCostLimitUsd", s.monthlyCostLimitUsd).toString()

    private val parameterRanges = SettingsOwnership.numericRanges(reportConfig) +
        SettingsOwnership.numericRanges(memoryConfig)

    fun parameterRange(key: String): ClosedFloatingPointRange<Float> = parameterRanges.getValue(key)

    private fun validParameter(key: String, value: Any): Float? {
        val range = parameterRanges[key] ?: return null
        val number = (value as? Number)?.toFloat() ?: return null
        return number.takeIf { it.isFinite() }?.coerceIn(range)
    }

    private fun saveParameter(key: String, json: String, publish: () -> Unit) {
        if (_state.value.parameterSaving) return
        retryParameter = { saveParameter(key, json, publish) }
        _state.update { it.copy(parameterSaving = true, parameterError = null, parameterMessage = null) }
        viewModelScope.launch {
            try {
                currentCoroutineContext().ensureActive()
                withContext(NonCancellable) {
                    moduleConfigStore.saveConfirmed(key, json)
                    publish()
                    syncFromConfigs()
                }
                retryParameter = null
                currentCoroutineContext().ensureActive()
                _state.update { it.copy(parameterMessage = "设置已保存") }
                traceManager.record("ui_agent_behavior", NodeType.CONFIG_CHANGE, "settings", "parameter_saved: $key")
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                _state.update { it.copy(parameterError = "保存参数失败，原设置保持不变，请重试") }
            } finally {
                _state.update { it.copy(parameterSaving = false) }
            }
        }
    }

    fun retryParameterSave() { retryParameter?.invoke() }

    /** Publish the confirmed candidate to the real runtime config; rejected changes stay local. */
    fun setReportParam(field: String, value: Any) {
        if (field !in SettingsOwnership.dedicatedFields("agent_runtime")) return
        val number = validParameter(field, value) ?: return
        val cfg = AgentRuntimeConfig().also { it.fromJson(reportConfig.toJson()) }
        when (field) {
            "baseIntervalSec" -> cfg.baseIntervalSec = number.toInt()
            "randomOffsetSec" -> cfg.randomOffsetSec = number.toInt()
            "intervalMultiplier" -> cfg.intervalMultiplier = number
            "maxDoublings" -> cfg.maxDoublings = number.toInt()
            "userPresenceWindowMin" -> cfg.userPresenceWindowMin = number.toInt()
        }
        val json = cfg.toJson()
        saveParameter("agent_runtime", json) { reportConfig.fromJson(json) }
    }

    /** 记忆自主行为参数（直写 MemoryModuleConfig，Reflection/图谱提取即时读取） */
    fun setMemoryParam(field: String, value: Any) {
        if (field !in SettingsOwnership.dedicatedFields("memory")) return
        val cfg = MemoryModuleConfig().also { it.fromJson(memoryConfig.toJson()) }
        when (field) {
            "enableReflection" -> cfg.enableReflection = value as? Boolean ?: return
            "reflectionHour" -> cfg.reflectionHour = (validParameter(field, value) ?: return).toInt()
            "graphExtractionThreshold" -> cfg.graphExtractionThreshold = (validParameter(field, value) ?: return).toInt()
        }
        val json = cfg.toJson()
        saveParameter("memory", json) { memoryConfig.fromJson(json) }
    }

    /** SD2：报点随发自驱开关（持久化 agent_behavior——AgentRuntime tick 消费） */
    fun setSelfDriveOnTick(enabled: Boolean) {
        saveBehavior(_state.value.copy(selfDriveOnTick = enabled))
    }

    /** 成本预算（存储值 — 强制执行随 Kernel 预算层接入，AGENT_KERNEL §3.2） */
    fun setBudget(dailyTokenLimit: Long? = null, monthlyCostLimitUsd: Float? = null) {
        if (monthlyCostLimitUsd != null && !monthlyCostLimitUsd.isFinite()) return
        saveBehavior(_state.value.copy(
            dailyTokenLimit = dailyTokenLimit?.coerceIn(8_000_000L, 100_000_000L) ?: _state.value.dailyTokenLimit,
            monthlyCostLimitUsd = monthlyCostLimitUsd?.coerceIn(5f, 50f) ?: _state.value.monthlyCostLimitUsd))
    }
}
