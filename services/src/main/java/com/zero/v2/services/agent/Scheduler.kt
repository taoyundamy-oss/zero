package com.zero.v2.services.agent

import com.zero.v2.core.bus.SystemBus
import com.zero.v2.core.common.FunctionResult
import com.zero.v2.core.moduleapi.*
import com.zero.v2.core.trace.AgentTraceManager
import com.zero.v2.core.trace.NodeType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton

/**
 * Scheduler — tick 到任务的 thin dispatch 层（DESIGN §2.4 / D21）。
 *
 * 唯一订阅 systemBus.onTick，遍历所有 AgentJob:
 *   `nextRunTime <= now` → execute()（串行分发，单 job 异常不阻塞其他 job）
 *
 * 不替代 SystemEventModule 的 PriorityQueue — 提醒队列仍由模块自持，
 * Scheduler 只负责"何时触发"（Step 3.4 起模块经 AgentJob 接入）。
 *
 * 实现 IZeroModule（同 FunctionRegistryModule 先例）: onStart() 开始收集 tick，
 * 随模块生命周期启动，无需额外调用方。
 */
@Singleton
class Scheduler @Inject constructor(
    private val systemBus: SystemBus,
    private val jobs: Set<@JvmSuppressWildcards AgentJob>,
    private val traceManager: AgentTraceManager,
    private val executionGuard: Provider<ToolExecutionGuard>,
) : IZeroModule {

    override val metadata = ModuleMetadata(
        id = "scheduler",
        displayName = "任务调度器",
        description = "订阅 Agent tick，按时分发到各 AgentJob（提醒/记忆/自动化任务）",
        category = ModuleCategory.CORE,
        launchOrder = -170  // AgentRuntime(-180) 之后、业务模块之前
    )

    private val _lifecycle = MutableStateFlow(ModuleLifecycle.UNINITIALIZED)
    override val lifecycle = _lifecycle.asStateFlow()
    override var isEnabled: Boolean = true
    override val requiredPermissions: List<String> = emptyList()
    override val config = object : ModuleConfigBase() {
        override fun toJson() = "{}"
        override fun fromJson(json: String) {}
        override fun defaultConfig() = this
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var collectJob: Job? = null

    /** Number of jobs with an actual next run; MAX_VALUE means paused, not pending. */
    fun pendingJobCount(): Int = jobs.count { it.nextRunTime != Long.MAX_VALUE }

    override suspend fun onAwake() { _lifecycle.value = ModuleLifecycle.AWAKE }
    override suspend fun onStart() {
        startCollecting()
        _lifecycle.value = ModuleLifecycle.STARTED
    }
    override suspend fun onDestroy() {
        collectJob?.cancel()
        scope.cancel()
        _lifecycle.value = ModuleLifecycle.DESTROYED
    }
    override suspend fun onConfigChanged(newConfig: ModuleConfigBase) {}

    override suspend fun checkAvailability(): Boolean = true
    override fun markDisabled() { collectJob?.cancel(); _lifecycle.value = ModuleLifecycle.DISABLED }

    private fun startCollecting() {
        if (collectJob?.isActive == true) return
        traceManager.record("scheduler", NodeType.SYSTEM_EVENT, metadata.id,
            "scheduler_started: jobs=${jobs.size} ids=[${jobs.joinToString { it.id }}]")
        // Subscribe before onStart returns: the application's first tick must not be lost.
        collectJob = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            systemBus.onTick.collect { now ->
                for (job in jobs) {
                    currentCoroutineContext().ensureActive()
                    if (job.nextRunTime <= now) {
                        try {
                            val result = if (job is IZeroModule) {
                                // Resolve lazily: the lifecycle manager owns this scheduler too.
                                // Guard the source module ID, not the independent scheduling ID.
                                executionGuard.get().executeForModule(job.metadata.id, "scheduled:${job.id}") {
                                    job.execute()
                                    FunctionResult.success("")
                                }
                            } else {
                                job.execute()
                                FunctionResult.success("")
                            }
                            currentCoroutineContext().ensureActive()
                            if (result.success) {
                                traceManager.record("scheduler", NodeType.SYSTEM_EVENT, metadata.id,
                                    "job_executed: id=${job.id} nextRun=${job.nextRunTime}")
                            } else {
                                traceManager.record("scheduler", NodeType.SYSTEM_EVENT, metadata.id,
                                    "job_not_run: id=${job.id} reason=${result.errorMessage?.take(60)}")
                            }
                        } catch (e: CancellationException) {
                            // Owner cancellation stops dispatch. A cancelled child task must not
                            // kill the long-lived tick subscription or masquerade as a crash.
                            currentCoroutineContext().ensureActive()
                            traceManager.record("scheduler", NodeType.SYSTEM_EVENT, metadata.id,
                                "job_cancelled: id=${job.id} reason=${e.message?.take(60)}")
                        } catch (e: Exception) {
                            // 单 job 异常只记录，不阻塞其他 job / 后续 tick
                            traceManager.record("scheduler", NodeType.ERROR, metadata.id,
                                "job_failed: id=${job.id} error=${e.message?.take(60)}")
                        }
                    }
                }
            }
        }
    }
}
