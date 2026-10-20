package com.zero.v2.services.agent

import com.zero.v2.core.bus.ChatBus
import com.zero.v2.core.bus.SystemBus
import com.zero.v2.core.common.MessageSource
import com.zero.v2.core.data.store.ModuleConfigStore
import com.zero.v2.core.moduleapi.*
import com.zero.v2.core.trace.AgentTraceManager
import com.zero.v2.core.trace.NodeType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.random.Random

/**
 * Agent Runtime — 自适应报点引擎（DESIGN §2.2 / D18 极简化）。
 *
 * 职责极简：只做定时发 tick + 管理报点间隔。
 * 不做 Memory 检查、不做 Graph 提取触发、不做 Reflection 决策。
 * 这些是各模块自己的事 — 模块订阅 systemBus.onTick，自己决定做什么。
 *
 * 自适应报点算法（S2R v5 终版 2026-09-19 · 仅参考 Alife 公式，方向自定 + 冲顶重置）:
 *   下次间隔 = (基础间隔 + 随机偏移) × 间隔倍数^min(连续触发次数, 最大翻倍次数)   ← 公式零改动
 *   计数三路（R10 终审收敛，零交集零重复）:
 *   ① 用户直输（CHAT_WINDOW/VOICE，onChatSent collect）→ count 步进 +1 封顶（连四到顶
 *      不重置：主人在用，安静伺服）；IM 入站等系统注入不在此计（防双重计数）
 *   ② 自主回合启动（noteTurnActivity，executeTurn 入口统一调用——IM 入站/tick 自驱/
 *      通知/云任务）→ count++；冲顶（>max）重置 0 —— 波次 sawtooth（90s→4.5m→13m→40m→2h ⟲）
 *   ③ 纯心跳周期（本周期无任何计数事件）→ count-- 闲置回密（activityThisCycle 条件化——
 *      无条件 -- 会把同周期回合的 ++ 抵消，波次退化为平档）
 *   （沿革：2026-09-18 反转定为"对话顶格/闲置递减"；v4 曾整体翻回 Alife 方向；
 *   v5 澄清废止 v4 方向——仅参考公式，方向如上三路）
 *
 * 应用在全部模块初始化完成后启动 tick；可选无障碍服务只提供屏幕与手势能力。
 * 设置页关闭/恢复控制循环；无障碍连接/断开不改变调度。
 *
 * 第三依赖说明: 设计稿为 LogManager，代码库中不存在，以 AgentTraceManager 承担日志职责。
 */
@Singleton
class AgentRuntime @Inject constructor(
    private val systemBus: SystemBus,
    private val chatBus: ChatBus,
    private val traceManager: AgentTraceManager,
    private val moduleConfigStore: ModuleConfigStore,
    private val triggerHub: dagger.Lazy<com.zero.v2.services.agent.TriggerHub>,  // Lazy breaks the construction cycle.
    private val rhythmPersistence: AgentRhythmPersistence? = null,
) : IZeroModule {

    override val metadata = ModuleMetadata(
        id = "agent_runtime",
        displayName = "Agent 运行时",
        description = "自适应报点引擎，驱动所有模块的定时行为",
        category = ModuleCategory.CORE,
        launchOrder = -180  // ChatBotModule(-200) 之后、其他模块之前
    )

    private val _lifecycle = MutableStateFlow(ModuleLifecycle.UNINITIALIZED)
    override val lifecycle = _lifecycle.asStateFlow()
    override var isEnabled: Boolean = true
    override val requiredPermissions: List<String> = emptyList()

    override val config: AgentRuntimeConfig = AgentRuntimeConfig()

    private var scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var loopJob: Job? = null
    private val autonomousCadence = AutonomousTickCadence()
    private var rhythmRestored = false

    private fun monotonicMs(): Long = System.nanoTime() / NANOS_PER_MILLISECOND

    @Synchronized private fun restoreRhythm() {
        if (rhythmRestored) return
        rhythmRestored = true
        val store = rhythmPersistence ?: return
        val now = System.currentTimeMillis()
        val saved = try { store.load() } catch (_: Exception) { null }
        if (saved != null) {
            val elapsed = (now - saved.savedAt).coerceAtLeast(0L)
            continuousTimerCount = saved.count.coerceIn(0, config.maxDoublings)
            activityThisCycle = saved.activity
            lastUserMsgAt = saved.lastUser.coerceAtMost(now)
            lastRealEventAt = saved.lastEvent.coerceAtMost(now)
            tickObservationMark = saved.observation.coerceAtMost(now)
            peakEnteredAt = saved.peak.coerceAtMost(now)
            deferring = saved.deferring
            autonomousCadence.restore(monotonicMs(), (saved.remainingMs - elapsed).coerceAtLeast(0L))
        } else if (store.restoreStatus != "missing") {
            // Invalid local state gets one bounded interval, not an immediate retry storm.
            autonomousCadence.schedule(monotonicMs(), calculateNextInterval())
        }
        traceManager.record("agent_runtime", NodeType.SYSTEM_EVENT, metadata.id,
            "rhythm_restored: status=${store.restoreStatus} tier=$continuousTimerCount " +
                "remainingMs=${autonomousCadence.outstanding(monotonicMs())}")
    }

    @Synchronized private fun persistRhythm() {
        if (!rhythmRestored) return
        val store = rhythmPersistence ?: return
        val saved = AgentRhythmSnapshot(System.currentTimeMillis(), autonomousCadence.outstanding(monotonicMs()),
            continuousTimerCount, activityThisCycle, lastUserMsgAt, lastRealEventAt,
            tickObservationMark, peakEnteredAt, deferring)
        val ok = try { store.save(saved) } catch (_: Exception) { false }
        if (!ok) traceManager.record("agent_runtime", NodeType.ERROR, metadata.id, "rhythm_save_failed")
    }

    /** 连续触发计数（v5 波次节奏自变量）：①用户直输步进封顶 / ②自主回合++冲顶重置 / ③纯心跳周期--回密 */
    @Volatile private var continuousTimerCount = 0
    /** S2R.1 ③：本周期（tick→等待→收尾）内出现过计数事件的脏标记——条件化 --，
     *  防止无条件 -- 抵消同周期回合的 ++（R10 终审：波次退化为平档陷阱） */
    @Volatile private var activityThisCycle = false

    // ═══ S2R.8（v6 · R15 裁定②）：顺延归零波次（取代 v5 冲顶即归零）═══
    // count 初达 MAX → 顶峰静默（tick 不开自驱回合）；后续 tick 有实质事件→顺延保持静默，
    // 干净→兑现归零新波次；顺延累计 >6h 强制归零。用户在场=顺延的自然结果。
    /** 最近实质事件时刻（顺延观察对象——✅源：用户消息/IM/白名单通知/BACKLOG/CLOUD_TASK/AUTOMATION/SELF_DRIVE 带载） */
    @Volatile private var lastRealEventAt = 0L
    /** 上次 tick 时的观察点（lastRealEventAt > 此值 = 本周期有实质事件） */
    @Volatile private var tickObservationMark = 0L
    /** 初达顶峰时刻（6h 强制归零基准） */
    @Volatile private var peakEnteredAt = 0L
    /** 顺延中标志（仅状态翻转时留痕，防每 tick 刷屏） */
    @Volatile private var deferring = false
    /** 最近用户直输时刻（在场静默保险窗——默认 0=禁用，R15 裁定⑥） */
    @Volatile private var lastUserMsgAt = 0L
    @Volatile private var isRunning = false
    /** 上次记录的报点 tier — 仅 tier 变化时埋点，避免每 tick 刷屏 */
    @Volatile private var lastTier = -1
    /** 当前等待协程 — 可被 kick() 打断，实现准点唤醒 */
    @Volatile private var delayJob: Job? = null

    companion object {
        private const val NANOS_PER_MILLISECOND = 1_000_000L
        /** S2R.1 ④：tick 发射后、区间求值前的 settle 窗口（tick 自驱回合 ++ 确定性入账） */
        private const val INTERVAL_SETTLE_MS = 300L
        /** S2R.8（v6 裁定②）：顶峰顺延累计上限——超过即强制归零（防背景噪声永久锁死） */
        private const val DEFERRAL_FORCE_RESET_MS = 6 * 3600_000L
    }

    /** Agent 宏观状态（D22）— 供 UI/调试观测 */
    private val _agentState = MutableStateFlow(AgentState.IDLE)
    val agentState: StateFlow<AgentState> = _agentState.asStateFlow()

    override suspend fun onAwake() {
        if (!scope.isActive) scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        _lifecycle.value = ModuleLifecycle.AWAKE
    }
    override suspend fun onStart() {
        // 不在这里提前发 tick：等待应用确认所有模块（尤其 Scheduler）初始化完成。
        _lifecycle.value = ModuleLifecycle.STARTED
    }
    override suspend fun onDestroy() {
        stopLoop()
        scope.cancel()
        _lifecycle.value = ModuleLifecycle.DESTROYED
    }
    override suspend fun onConfigChanged(newConfig: ModuleConfigBase) { /* 报点参数热更新，无需重启 */ }

    override suspend fun checkAvailability(): Boolean = true
    override fun markDisabled() { stopLoop(); _lifecycle.value = ModuleLifecycle.DISABLED }

    /** 应用完成模块初始化或设置页恢复模式后调用；持久化 OFF 跨重启仍不启动。 */
    @Synchronized
    fun startLoop() {
        if (loopJob?.isActive == true) return
        loopJob = scope.launch(start = CoroutineStart.LAZY) {
            val off = try {
                moduleConfigStore.get("agent_behavior")?.let {
                    org.json.JSONObject(it).optString("mode") == "OFF"
                } == true
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) { false }
            if (off) {
                traceManager.record("agent_runtime", NodeType.SYSTEM_EVENT, metadata.id,
                    "loop_blocked_by_mode: OFF（设置 → Agent 自主行为）")
                return@launch
            }
            coroutineScope {
                synchronized(this@AgentRuntime) {
                    coroutineContext.ensureActive()
                    startLoopInternal(this)
                }
            }
        }
        loopJob?.start()
    }

    private fun startLoopInternal(loopScope: CoroutineScope) {
        if (isRunning) return
        restoreRhythm()
        isRunning = true
        lastTier = -1
        val cfg = config
        traceManager.record("agent_runtime", NodeType.SYSTEM_EVENT, metadata.id,
            "loop_started: base=${cfg.baseIntervalSec}s offset=±${cfg.randomOffsetSec}s " +
                    "mult=${cfg.intervalMultiplier} maxDoublings=${cfg.maxDoublings}")

        // 用户直输 → 步进安静（S2R.1 ① · v5：单条 +1 封顶，连四到顶不重置——持续对话持续
        // 深安静）。仅 CHAT_WINDOW/VOICE 计"主人活跃"：IM 入站等系统注入回合的 userMsg
        // source=AUTONOMOUS，不在此计（其计数统一走 noteTurnActivity ②——零交集防双重计数）
        loopScope.launch {
            chatBus.onChatSent.collect { event ->
                noteUserMessage(event.message.source)
            }
        }

        loopScope.launch {
            while (isActive) {
                val cadenceDue = autonomousCadence.isDue(monotonicMs())
                if (cadenceDue) {
                    // Reserve before entering a model/tool path: process death cannot erase admission.
                    autonomousCadence.schedule(monotonicMs(), calculateNextInterval())
                    persistRhythm()
                }
                try {
                    _agentState.value = AgentState.OBSERVING
                    systemBus.onTick.emit(System.currentTimeMillis())
                    if (cadenceDue) {
                        processAutonomousTick()
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (e: Exception) {
                    traceManager.record("agent_runtime", NodeType.ERROR, metadata.id,
                        "tick_emit_failed: ${e.message?.take(60)}")
                }
                // S2R.1 ④：区间求值前 300ms settle——tick 自驱回合的 noteTurnActivity（②）
                // 在此窗口确定性入账，calculateNextInterval 按已入账计数求值（波次序列精确不漂档）
                delay(INTERVAL_SETTLE_MS)
                if (cadenceDue) {
                    autonomousCadence.schedule(monotonicMs(), calculateNextInterval())
                    persistRhythm()
                }
                // 可打断等待 — kick() 取消 delayJob 提前进入下一轮（提醒/定时任务准点）
                delayJob = launch { delay(autonomousCadence.remaining(monotonicMs())) }
                delayJob?.join()
                delayJob = null
                // S2R.1 ③（R10 终审条件化）：本周期有计数事件（②回合 ++ / ①直输步进）→ 跳过
                // -- 仅清脏标；纯心跳周期（开关关/日顶满/无事件）才递减回密（闲置 → 90s 纯心跳）
                finishCadenceCycle()
                _agentState.value = AgentState.IDLE

                // 报点节奏可观测: 仅 tier 变化时记录（tier 回落 = 打断重置可见）
                val tier = minOf(continuousTimerCount, config.maxDoublings)
                if (tier != lastTier) {
                    lastTier = tier
                    traceManager.record("agent_runtime", NodeType.SYSTEM_EVENT, metadata.id,
                        "tick_interval_changed: tier=$tier nextIntervalMs=${calculateNextInterval()}")
                }
            }
        }
    }

    @Synchronized internal fun noteUserMessage(source: MessageSource, now: Long = System.currentTimeMillis()) {
        if (source != MessageSource.CHAT_WINDOW && source != MessageSource.VOICE) return
        restoreRhythm()
        activityThisCycle = true
        lastUserMsgAt = now
        lastRealEventAt = lastUserMsgAt
        continuousTimerCount = minOf(continuousTimerCount + 1, config.maxDoublings)
        if (continuousTimerCount == config.maxDoublings && peakEnteredAt == 0L) peakEnteredAt = lastUserMsgAt
        traceManager.record("agent_runtime", NodeType.SYSTEM_EVENT, metadata.id,
            "user_msg_step: source=$source count=$continuousTimerCount")
        persistRhythm()
    }

    @Synchronized private fun finishCadenceCycle() {
        if (!autonomousCadence.isDue(monotonicMs())) return
        if (activityThisCycle) activityThisCycle = false
        else if (continuousTimerCount in 1 until config.maxDoublings) continuousTimerCount--
        persistRhythm()
    }

    internal suspend fun processAutonomousTick(now: Long = System.currentTimeMillis()) {
        // ═══ S2R.8（v6 顺延归零）：顶峰判定先行（先于 fireSelfDrive）═══
        val userInCycle = lastUserMsgAt > tickObservationMark
        val atPeak = continuousTimerCount >= config.maxDoublings && config.maxDoublings > 0
        if (atPeak) {
            // 顶峰静默：tick 不开自驱回合——顺延或兑现归零二选一
            val hadRealEvent = lastRealEventAt > tickObservationMark
            val deferredMs = now - peakEnteredAt
            when {
                userInCycle || (hadRealEvent && deferredMs <= DEFERRAL_FORCE_RESET_MS) -> {
                    if (!deferring) {
                        deferring = true
                        traceManager.record("agent_runtime", NodeType.SYSTEM_EVENT, metadata.id,
                            "wave_deferred: 顶峰顺延（实质事件持续，保持静默）")
                    }
                }
                deferredMs > DEFERRAL_FORCE_RESET_MS -> {
                    continuousTimerCount = 0; deferring = false; peakEnteredAt = 0L
                    traceManager.record("agent_runtime", NodeType.SYSTEM_EVENT, metadata.id,
                        "wave_force_reset: 顺延超 6h 强制归零（防背景噪声锁死）")
                }
                else -> {
                    continuousTimerCount = 0; deferring = false; peakEnteredAt = 0L
                    traceManager.record("agent_runtime", NodeType.SYSTEM_EVENT, metadata.id,
                        "wave_reset: 干净报点兑现归零，新波次起跑")
                }
            }
            tickObservationMark = now
        } else {
            deferring = false
            tickObservationMark = now
            // S2R.8（裁定⑥）：在场静默保险窗（默认 0=禁用——实质事件步进+顺延
            // 已是主判定，此窗纯保险）
            val presenceMin = config.userPresenceWindowMin
            val userRecent = presenceMin > 0 &&
                    lastUserMsgAt > 0 && now - lastUserMsgAt < presenceMin * 60_000L
            // SD2（2026-09-18 用户裁定②）：系统提示词随报点节奏发给 LLM 自驱——
            // "始终在线自驱"。自适应间隔天然节流；频次兜底=TriggerHub 每日触发上限
            if (!userRecent && !userInCycle) {
                fireTickIfEnabled()
            }
        }
        persistRhythm()
    }

    private suspend fun fireTickIfEnabled() {
        try {
            val behavior = moduleConfigStore.get("agent_behavior")
            if (behavior != null && org.json.JSONObject(behavior).optBoolean("selfDriveOnTick", false)) {
                triggerHub.get().fireSelfDrive("", reason = "tick")
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            traceManager.record("agent_runtime", NodeType.ERROR, metadata.id,
                "self_drive_tick_failed: ${e.message?.take(60)}")
        }
    }

    @Synchronized
    fun stopLoop() {
        persistRhythm()
        loopJob?.cancel()
        loopJob = null
        isRunning = false
        delayJob?.cancel()
        delayJob = null
        traceManager.record("agent_runtime", NodeType.SYSTEM_EVENT, metadata.id,
            "loop_stopped: totalTicksTier=$lastTier")
    }

    /**
     * 外部唤醒提示 — 取消当前等待，立即发下一 tick。
     *
     * 用途: 提醒/定时任务到期前的一次性精准唤醒（到期时刻 kick → tick → Scheduler 执行 AgentJob）。
     * AgentRuntime 仍是唯一 tick 发射源（D19）；kick 只是打断等待节奏，不是第二个心跳。
     */
    fun kick() {
        val job = delayJob ?: return
        job.cancel()
        traceManager.record("agent_runtime", NodeType.SYSTEM_EVENT, metadata.id,
            "tick_kicked: 提前唤醒（定时任务准点）")
    }

    /**
     * 窗口切换活动（AccessibilityService TYPE_WINDOW_STATE_CHANGED 转发）。
     * 2026-09-18 用户裁定（真机实测）：**不再重置报点退避**——三方通知弹出/任意前台
     * 切换都会打进来，退避恒失效致报点贴基距狂奔（自驱回合过频烧 token）。
     * 打断重置唯一源 = 用户对话（chatBus.onChatSent，见上）——无对话即按闲置退避。
     */
    fun onUserActivity() { /* no-op：窗口活动不重置（仅对话打断重置） */ }

    /**
     * 自主回合启动计数（S2R.1 ② · v6 顺延归零修订）——AutonomousThinkingJob.executeTurn
     * 入口统一调用（一切自主回合含被跳过/丢弃回合——R15 裁定④：事件已按点发生应计入）。
     * count++ **封顶 MAX 不再冲顶重置**（v5 overflow-reset 由顺延归零取代——根治"在场期
     * 归零穿透用户步进"缺陷，R15 探索核证）；初达 MAX 记 peakEnteredAt（6h 强制基准）。
     */
    @Synchronized fun noteTurnActivity() {
        restoreRhythm()
        activityThisCycle = true
        if (continuousTimerCount < config.maxDoublings) {
            continuousTimerCount++
            if (continuousTimerCount == config.maxDoublings) {
                peakEnteredAt = System.currentTimeMillis()
            }
        }
        traceManager.record("agent_runtime", NodeType.SYSTEM_EVENT, metadata.id,
            "note_turn_activity: count=$continuousTimerCount" +
                    if (continuousTimerCount >= config.maxDoublings) " peak=true" else "")
        persistRhythm()
    }

    /**
     * S2R.8（v6 · R15 裁定②④）：实质事件登记——顺延观察对象（✅源：IM 入站/白名单通知/
     * BACKLOG / CLOUD_TASK / AUTOMATION / SELF_DRIVE 带载；❌：BATTERY 系 /
     * SCHEDULED_PLAYBACK / 报点自驱空载——由调用方 AutonomousThinkingJob.onTrigger 分类）。
     * 只更新 lastRealEventAt（计数仍走 ①/② 各自路径——本方法零计数防双计）。
     * 事件到达即登记（含被丢弃/排队的事件——裁定④"按点发生"语义）。
     */
    @Synchronized fun noteRealEvent(source: String) {
        restoreRhythm()
        lastRealEventAt = System.currentTimeMillis()
        persistRhythm()
    }

    /**
     * S2R.9 补门（用户裁定 2026-09-20"跑前续查"）：任务续跑重排前的开关续查——
     * mode=OFF（全自主停摆）或 trigger_hub.selfDriveEnabled=false（自驱门关）时拦截。
     * 安全三则：①suspend 异步读（调用点在 Default 调度器协程，非主线程零阻塞）；
     * ②异常/读不到一律放行（fail-open——读失败不吞掉 S2R.9 既有续跑功能）；
     * ③零写副作用（纯读）。
     */
    suspend fun isTaskResumeAllowed(): Boolean {
        return try {
            val behavior = moduleConfigStore.get("agent_behavior")
            if (behavior != null && org.json.JSONObject(behavior).optString("mode") == "OFF") return false
            val trigger = moduleConfigStore.get("trigger_hub")
            trigger == null || org.json.JSONObject(trigger).optBoolean("selfDriveEnabled", true)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            true   // fail-open：配置读取异常不阻断续跑（保既有功能）
        }
    }

    /**
     * 计算下次报点间隔（ms）。
     * 下次间隔 = (基础间隔 + 随机偏移) × 间隔倍数^min(连续触发次数, 最大翻倍次数)
     */
    fun calculateNextInterval(): Long {
        val cfg = config
        val base = cfg.baseIntervalSec +
                Random.nextInt(-cfg.randomOffsetSec, cfg.randomOffsetSec + 1)
        val safeBase = maxOf(base, 10)
        val doubled = minOf(continuousTimerCount, cfg.maxDoublings)
        val multiplier = Math.pow(cfg.intervalMultiplier.toDouble(), doubled.toDouble()).toLong()
        return maxOf(safeBase * multiplier * 1000L, 10_000L)
    }
}

