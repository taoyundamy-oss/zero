package com.zero.v2.app.system

import com.zero.v2.core.moduleapi.ModuleLifecycle
import com.zero.v2.core.moduleapi.ModuleRuntimeStateReader
import com.zero.v2.core.moduleapi.StatusFact
import com.zero.v2.core.moduleapi.SystemSnapshot
import com.zero.v2.core.moduleapi.SystemStateAssembler
import com.zero.v2.core.moduleapi.SystemStateAssembler.BudgetInputs
import com.zero.v2.core.moduleapi.SystemStateAssembler.ProfileInput
import com.zero.v2.core.moduleapi.SystemStateAssembler.SystemStateInputs
import com.zero.v2.core.moduleapi.SystemStateAssembler.TermuxInputs
import com.zero.v2.core.moduleapi.SystemStateAssembler.VoiceInputs
import com.zero.v2.core.moduleapi.SystemStateRepository
import com.zero.v2.core.moduleapi.ZeroStatusResult
import com.zero.v2.services.agent.AgentRuntime
import com.zero.v2.services.agent.BudgetGuard
import com.zero.v2.services.llm.LlmProfile
import com.zero.v2.services.llm.LlmProfileStore
import com.zero.v2.services.realtime.LocalVoiceProvider
import com.zero.v2.services.termux.TermuxBridge
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton

/**
 * [SystemStateRepository] 实现——聚合各 owner 事实的只读投影。
 *
 * owner：ModuleRuntimeStateReader（模块运行态）、BudgetGuard（预算）、AgentRuntime（回合）、
 * LlmProfileStore（模型路由）、TermuxBridge（supervisor health）、LocalVoiceProvider（语音会话）。
 * 只读投影，不写第二套状态。动作执行前仍由原始 owner 实时校验。
 *
 * TermuxBridge.health() 与 LocalVoiceProvider.state 为流/挂起，用后台 scope：
 *   - termux 后台探活（30s）缓存；探活失败标 UNKNOWN（不当离线断言）。
 *   - 语音会话状态流收集——状态变化即时 refresh，UI liveActive 近实时。
 * [snapshots] 为响应式投影流（UI 订阅）；[snapshot] 为按需新鲜读（zero_status FC）。
 * revision 每次刷新递增。
 */
@Singleton
class SystemStateRepositoryImpl @Inject constructor(
    private val runtimeStateReader: Provider<ModuleRuntimeStateReader>,
    private val budgetGuard: Provider<BudgetGuard>,
    private val agentRuntime: Provider<AgentRuntime>,
    private val profileStore: Provider<LlmProfileStore>,
    private val termuxBridge: Provider<TermuxBridge>,
    private val voiceProvider: Provider<LocalVoiceProvider>,
    private val deviceState: DeviceStateReader,
    private val scheduler: Provider<com.zero.v2.services.agent.Scheduler>,
    private val retrievalReader: RetrievalStatusReader,
    private val chatEngine: Provider<com.zero.v2.services.realtime.ChatEngineBridge>,
    private val llmClient: Provider<com.zero.v2.services.llm.OpenAiCompatibleClient>,
    private val releaseManager: Provider<com.zero.v2.services.termux.ReleaseManager>,
) : SystemStateRepository {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val revCounter = AtomicLong(0)
    private val _revision = MutableStateFlow(0L)
    override val revision = _revision.asStateFlow()

    private val _snapshots = MutableStateFlow(defaultSnapshot())
    override val snapshots = _snapshots.asStateFlow()

    /** 最近 Termux health 缓存（后台探活更新；未探活=UNKNOWN）。 */
    @Volatile private var termuxCache: TermuxInputs = TermuxInputs(alive = false, port = 7878, pid = null)
    @Volatile private var budgetLimits = 20_000_000L to 10.0f
    private var lastInputs: SystemStateInputs? = null
    @Volatile private var resources = com.zero.v2.core.moduleapi.ResourceStatus()
    @Volatile private var permissions = emptyList<com.zero.v2.core.moduleapi.PermissionStatus>()
    private var lastFreshness: List<StatusFact>? = null
    private data class CredentialObservation(val profile: LlmProfile, val configured: Boolean?, val observedAt: Long,
        val identity: com.zero.v2.services.llm.ModelRequestHealth.Identity?, val credentialRevision: Long)
    @Volatile private var credentialObservations = emptyMap<String, CredentialObservation>()

    init {
        scope.launch {
            while (true) {
                try {
                    val now = System.currentTimeMillis()
                    credentialObservations = profileStore.get().profiles.value.associate { profile ->
                        val observation = try {
                            val client = llmClient.get()
                            val generation = client.credentialRevision
                            val key = client.resolveApiKey(profile)
                            CredentialObservation(profile, com.zero.v2.services.llm.CloudflareChatTransport.credentialReady(profile, key), now,
                                client.requestHealth.identity(profile, key, "status"),
                                generation)
                        } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                        catch (_: Exception) { CredentialObservation(profile, null, 0L, null, -1L) }
                        profile.id to observation
                    }
                    resources = deviceState.resources(now)
                    permissions = deviceState.permissions(now)
                    retrievalReader.refresh()
                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    // Keep the last observation timestamp so callers can see staleness.
                }
                refresh()
                kotlinx.coroutines.delay(15_000)
            }
        }
        // 后台定期探活 termux（30s）；失败不抛，保留 UNKNOWN。
        scope.launch {
            while (true) {
                try {
                    val bridge = termuxBridge.get()
                    val port = bridge.supervisorPort
                    val health = bridge.health()
                    termuxCache = if (health != null) {
                        val metadata = com.zero.v2.services.termux.DeploymentStatusDecoder.health(
                            health.raw, port, System.currentTimeMillis())
                        val ids = metadata.runtimeModules.orEmpty().map { it.id }
                        val deployments = ids.take(50).chunked(4).flatMap { batch ->
                            kotlinx.coroutines.coroutineScope {
                                batch.map { id -> asyncStatus(id) }.map { it.await() }
                            }
                        }
                        metadata.copy(deployments = deployments, deploymentTruncated = ids.size > 50)
                    } else {
                        TermuxInputs(alive = false, port = port, pid = null)
                    }
                } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled } catch (_: Exception) {
                    termuxCache = termuxCache.copy(alive = false)
                }
                try { budgetLimits = budgetGuard.get().readLimits() }
                catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                catch (_: Exception) { /* Retain configured limits, independently of supervisor health. */ }
                refresh()
                kotlinx.coroutines.delay(30_000)
            }
        }
        scope.launch {
            while (true) {
                refresh()
                kotlinx.coroutines.delay(1000)
            }
        }
        // 语音会话状态流——状态变化即时 refresh（UI liveActive 近实时，不依赖 30s 周期）。
        scope.launch {
            try {
                voiceProvider.get().state.collect { _ ->
                    refresh()
                }
            } catch (_: Exception) {
                // LocalVoiceProvider 不可用：保留默认 voice=inactive
            }
        }
    }

    private fun CoroutineScope.asyncStatus(id: String) = async {
        try { releaseManager.get().readStatus(id) }
        catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (_: Exception) { com.zero.v2.services.termux.DeploymentStatusDecoder.unknown(id, "owner_read_failed") }
    }

    override fun snapshot(): SystemSnapshot {
        refresh()
        return _snapshots.value
    }

    override fun zeroStatus(sections: Set<String>, sinceRevision: Long?, limit: Int, cursor: String?): ZeroStatusResult =
        SystemStateAssembler.zeroStatus(snapshot(), sections, sinceRevision, limit, cursor)

    /** 读各 owner 当前事实 → Assembler 输入（同步读缓存/只读流 value，不触发 suspend）。 */
    private fun readInputs(): SystemStateInputs {
        val now = System.currentTimeMillis()
        val reader = runtimeStateReader.get()
        val states = reader.runtimeStates.value
        val budget = budgetGuard.get().snapshot()
        val (dayTokens, monthTokens, monthUsd) = budget
        val agent = if (!chatEngine.get().isConversationIdle()) "conversation" else agentRuntime.get().agentState.value.name
        val profiles = profileStore.get().profiles.value.map { it.toInput(now) }
        val retrieval = retrievalReader.observation
        val voice = try {
            val vp = voiceProvider.get()
            VoiceInputs(active = vp.isActive(), state = vp.state.value.name)
        } catch (_: Exception) {
            VoiceInputs(active = false, state = "IDLE")
        }
        return SystemStateInputs(
            modules = states,
            budget = BudgetInputs(
                dayTokens = dayTokens, monthTokens = monthTokens, monthUsd = monthUsd,
                dailyLimit = budgetLimits.first, monthlyLimitUsd = budgetLimits.second,
            ),
            agentStateName = agent,
            termux = termuxCache,
            profiles = profiles,
            memoryAvailable = if (states["memory"]?.operational == true) StatusFact.LIVE else StatusFact.UNKNOWN,
            knowledgeAvailable = if (states["knowledge"]?.operational == true) StatusFact.LIVE else StatusFact.UNKNOWN,
            indexVersion = retrieval.indexVersion,
            retrievalBacklog = retrieval.backlog,
            retrievalObservedAt = retrieval.observedAt,
            retrievalDomains = retrieval.domains,
            extractionJobs = retrieval.extractionJobs,
            extractionObservedAt = retrieval.extractionObservedAt,
            pendingJobs = scheduler.get().pendingJobCount(),
            resources = resources,
            permissions = permissions,
            now = now,
            voice = voice,
        )
    }

    /** 刷新快照 + 递增 revision（后台探活/语音状态变化触发）。 */
    @Synchronized
    private fun refresh() {
        val inputs = readInputs()
        val comparable = inputs.copy(now = 0L)
        val assembled = SystemStateAssembler.assemble(inputs, 0L)
        val freshness = assembled.termux.deployments.map { it.freshness } +
            assembled.modelRoutes.map { it.availability } + listOf(assembled.termux.connection,
            com.zero.v2.core.moduleapi.StatusProjection.freshness(inputs.retrievalObservedAt, inputs.now,
                SystemStateAssembler.TTL_RETRIEVAL_MS),
            com.zero.v2.core.moduleapi.StatusProjection.freshness(inputs.resources.observedAt, inputs.now,
                SystemStateAssembler.TTL_RUNTIME_MS)) + assembled.retrieval.domains.map { it.freshness } +
            inputs.permissions.map { com.zero.v2.core.moduleapi.StatusProjection.freshness(
                it.observedAt, inputs.now, SystemStateAssembler.TTL_MODULE_MS) }
        val rev = if (comparable != lastInputs || freshness != lastFreshness) revCounter.incrementAndGet() else _revision.value
        lastInputs = comparable
        lastFreshness = freshness
        _revision.value = rev
        _snapshots.value = assembled.copy(revision = rev, snapshotId = "snap_${inputs.now}_$rev")
    }

    /** 启动前默认空快照（voice inactive，owners 未探活=UNKNOWN）。 */
    private fun defaultSnapshot(): SystemSnapshot = SystemStateAssembler.assemble(
        SystemStateInputs(
            modules = emptyMap(),
            budget = BudgetInputs(0, 0, 0.0, 20_000_000L, 10.0f),
            agentStateName = "IDLE",
            termux = TermuxInputs(alive = false, port = 7878, pid = null),
            profiles = emptyList(),
            memoryAvailable = StatusFact.UNKNOWN,
            knowledgeAvailable = StatusFact.UNKNOWN,
            indexVersion = null,
            retrievalBacklog = null,
            now = 0L,
        ), revision = 0L,
    )

    private fun LlmProfile.toInput(now: Long): ProfileInput {
        val observation = credentialObservations[id]?.takeIf {
            it.profile == this && now - it.observedAt in 0..SystemStateAssembler.TTL_MODULE_MS &&
                it.credentialRevision % 2L == 0L && it.credentialRevision == llmClient.get().credentialRevision
        }
        val health = llmClient.get().requestHealth.observation(observation?.identity)
        return ProfileInput(id, kind.name, enabled, observation?.configured, observation?.observedAt ?: 0L,
            capabilities.map { it.name }, health?.operation, health?.outcome, health?.httpStatus, health?.observedAt ?: 0L)
    }
}
