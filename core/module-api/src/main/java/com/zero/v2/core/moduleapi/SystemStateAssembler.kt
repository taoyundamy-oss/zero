package com.zero.v2.core.moduleapi

/**
 * 系统状态组装器——纯逻辑，把各 owner 的原始事实投影为 [SystemSnapshot] / [ZeroStatusResult]。
 *
 * 与 Android owner 读取分离：[SystemStateRepositoryImpl] 负责读取 owner（BudgetGuard、
 * AgentRuntime、TermuxBridge、LlmProfileStore、ModuleRuntimeStateReader）并转成
 * [SystemStateInputs]，本类负责组装、脱敏、摘要与 sections 提取。便于单测。
 *
 * 脱敏原则：输出不含 Key、baseUrl 完整值、消息正文、文件内容、通知文本；模型路由只暴露
 * profileId/kind/enabled/availability，余额只暴露档位不暴露精确值。
 */
object SystemStateAssembler {

    /** 默认 TTL（总纲§4建议）：资源/任务 15s，模块/权限 30s，索引 60s。 */
    const val TTL_RUNTIME_MS = 15_000L
    const val TTL_MODULE_MS = 30_000L
    const val TTL_RETRIEVAL_MS = 60_000L

    /** 各 owner 原始输入（module-api 内类型 + 原语，不依赖 services）。 */
    data class SystemStateInputs(
        val modules: Map<String, ModuleRuntimeState>,
        val budget: BudgetInputs,
        val agentStateName: String,
        val termux: TermuxInputs,
        val profiles: List<ProfileInput>,
        val memoryAvailable: StatusFact,
        val knowledgeAvailable: StatusFact,
        val indexVersion: String?,
        val retrievalBacklog: Int?,
        val now: Long,
        val voice: VoiceInputs = VoiceInputs(active = false, state = "IDLE"),
        val pendingJobs: Int? = null,
        val retrievalObservedAt: Long = 0L,
        val resources: ResourceStatus = ResourceStatus(),
        val permissions: List<PermissionStatus> = emptyList(),
        val retrievalDomains: List<RetrievalDomainStatus> = emptyList(),
        val extractionJobs: Int? = null,
        val extractionObservedAt: Long = 0L,
    )
    data class BudgetInputs(
        val dayTokens: Long, val monthTokens: Long, val monthUsd: Double,
        val dailyLimit: Long, val monthlyLimitUsd: Float,
    )
    data class TermuxInputs(
        val alive: Boolean, val port: Int, val pid: Int?, val observedAt: Long = 0L,
        val rootfs: Boolean? = null, val sdkVersion: String? = null, val releaseProtocol: Int? = null,
        val runtimeModules: List<DynamicRuntimeStatus>? = null,
        val deployments: List<ModuleDeploymentStatus> = emptyList(), val deploymentTruncated: Boolean = false,
    )
    data class ProfileInput(
        val id: String, val kind: String, val enabled: Boolean,
        val keyConfigured: Boolean?, val credentialObservedAt: Long = 0L,
        val capabilities: List<String> = emptyList(),
        val requestOperation: String? = null, val requestOutcome: String? = null,
        val httpStatus: Int? = null, val requestObservedAt: Long = 0L,
    )
    /** 语音会话原始输入（源 = LocalVoiceProvider.isActive/state）。 */
    data class VoiceInputs(val active: Boolean, val state: String)

    /** 组装完整快照。 */
    fun assemble(inputs: SystemStateInputs, revision: Long): SystemSnapshot {
        val now = inputs.now
        return SystemSnapshot(
            snapshotId = "snap_${now}_${revision}",
            revision = revision,
            capturedAt = now,
            modules = inputs.modules.map { (id, s) ->
                ModuleStatus(
                    id = id,
                    lifecycle = s.lifecycle.name,
                    operational = s.operational,
                    reason = if (!s.operational && s.lifecycle == ModuleLifecycle.DISABLED) "disabled" else null,
                    observedAt = now,
                    staleAfterMs = TTL_MODULE_MS,
                )
            }.sortedBy { it.id },
            modelRoutes = inputs.profiles.map { p ->
                ModelRouteStatus(
                    profileId = p.id,
                    kind = p.kind,
                    enabled = p.enabled,
                    // A resolved credential is configuration evidence, not a successful provider request.
                    availability = when (StatusProjection.freshness(p.requestObservedAt, now, TTL_MODULE_MS)) {
                        StatusFact.LIVE -> if (p.httpStatus in 200..299) StatusFact.LIVE else StatusFact.UNKNOWN
                        StatusFact.STALE -> StatusFact.STALE
                        StatusFact.UNKNOWN -> StatusFact.UNKNOWN
                    },
                    observedAt = p.requestObservedAt,
                    keyConfigured = p.keyConfigured,
                    credentialObservedAt = p.credentialObservedAt,
                    capabilities = p.capabilities.sorted(),
                    requestOperation = p.requestOperation,
                    requestOutcome = p.requestOutcome,
                    httpStatus = p.httpStatus,
                )
            }.sortedBy { it.profileId },
            runtime = RuntimeStatus(
                turnOwner = if (inputs.agentStateName.equals("IDLE", ignoreCase = true)) "idle" else inputs.agentStateName.lowercase(),
                pendingJobs = inputs.pendingJobs,
                lastFailureCode = null,
                observedAt = now,
            ),
            termux = TermuxStatus(
                connection = when {
                    !inputs.termux.alive -> StatusFact.UNKNOWN
                    else -> StatusProjection.freshness(inputs.termux.observedAt, now, TTL_MODULE_MS)
                },
                supervisorPort = inputs.termux.port,
                pid = inputs.termux.pid,
                deploymentState = if (inputs.termux.alive) "supervisor_alive" else "unknown",
                observedAt = inputs.termux.observedAt,
                rootfs = inputs.termux.rootfs,
                sdkVersion = inputs.termux.sdkVersion,
                releaseProtocol = inputs.termux.releaseProtocol,
                runtimeModules = inputs.termux.runtimeModules,
                deployments = inputs.termux.deployments.map {
                    it.copy(freshness = if (it.errorCode != null || !inputs.termux.alive) StatusFact.UNKNOWN
                        else StatusProjection.freshness(it.observedAt, now, TTL_MODULE_MS))
                },
                deploymentTruncated = inputs.termux.deploymentTruncated,
            ),
            budgets = budgetClass(inputs.budget, now),
            retrieval = RetrievalStatus(
                memoryAvailable = inputs.memoryAvailable,
                knowledgeAvailable = inputs.knowledgeAvailable,
                indexVersion = inputs.indexVersion,
                backlog = inputs.retrievalBacklog,
                observedAt = inputs.retrievalObservedAt,
                domains = inputs.retrievalDomains.map {
                    it.copy(freshness = if (it.readError != null) StatusFact.UNKNOWN
                        else StatusProjection.freshness(it.observedAt, now, TTL_RETRIEVAL_MS))
                },
                extractionJobs = inputs.extractionJobs,
                extractionObservedAt = inputs.extractionObservedAt,
            ),
            voice = VoiceSessionStatus(
                active = inputs.voice.active,
                state = inputs.voice.state,
                observedAt = now,
            ),
            resources = inputs.resources,
            permissions = inputs.permissions,
        )
    }

    /** 预算档位：不暴露精确余额，仅 ok/low/exceeded + 日重置时间。 */
    private fun budgetClass(b: BudgetInputs, now: Long): BudgetStatus {
        val remainingClass = when {
            b.monthUsd >= b.monthlyLimitUsd || b.dayTokens >= b.dailyLimit -> "exceeded"
            b.monthUsd >= b.monthlyLimitUsd * 0.8 || b.dayTokens >= b.dailyLimit * 0.8 -> "low"
            else -> "ok"
        }
        val nextDayReset = nextMidnight(now)
        return BudgetStatus(remainingClass = remainingClass, nextResetAt = nextDayReset, observedAt = now)
    }

    /** ≤200 token 摘要（注入回合 context）：只含可用能力、正在执行、相关故障。 */
    fun brief(s: SystemSnapshot): String {
        val ops = s.modules.count { it.operational }
        val disabled = s.modules.filter { !it.operational }.joinToString(",") { it.id }.take(60)
        val termux = if (s.termux.connection == StatusFact.LIVE) "termux:ok" else "termux:unknown"
        val budget = "budget:${s.budgets.remainingClass}"
        val configuredModels = s.modelRoutes.count { it.enabled && it.keyConfigured == true }
        val unknownCredentials = s.modelRoutes.count { it.enabled && it.keyConfigured == null }
        val sb = StringBuilder()
        sb.append("modules:$ops/${s.modules.size} ").append(termux).append(' ').append(budget)
            .append(" models_configured:$configuredModels credentials_unknown:$unknownCredentials")
            .append(" models_recent_http_ok:${s.modelRoutes.count { it.enabled && it.availability == StatusFact.LIVE }}")
        if (s.runtime.turnOwner != "idle") sb.append(" running:${s.runtime.turnOwner}")
        if (s.voice.active) sb.append(" voice:${s.voice.state.lowercase()}")
        if (s.runtime.lastFailureCode != null) sb.append(" lastErr:${s.runtime.lastFailureCode}")
        if (disabled.isNotEmpty()) sb.append(" disabled:$disabled")
        return sb.toString().take(800)   // 800 字符 ≈ 200 token 上限
    }

    /** zero_status：按 sections 只读分页提取 + 脱敏 + unknown/stale/truncated。 */
    fun zeroStatus(
        snapshot: SystemSnapshot,
        sections: Set<String>,
        sinceRevision: Long?,
        limit: Int,
        cursor: String? = null,
    ): ZeroStatusResult {
        if (cursor == null && sinceRevision != null && snapshot.revision <= sinceRevision) {
            return ZeroStatusResult(
                revision = snapshot.revision, capturedAt = snapshot.capturedAt,
                facts = emptyMap(), unknown = emptyList(), stale = emptyList(),
                truncated = false, nextCursor = null,
            )
        }
        val facts = LinkedHashMap<String, Any>()
        val unknown = mutableListOf<String>()
        val stale = mutableListOf<String>()
        val now = snapshot.capturedAt
        for (sec in sections.sorted()) {
            when (sec) {
                "modules" -> facts["modules"] = snapshot.modules.map {
                    mapOf("id" to it.id, "operational" to it.operational, "lifecycle" to it.lifecycle)
                }
                "models" -> {
                    facts["models"] = snapshot.modelRoutes.map {
                        mapOf("profileId" to it.profileId, "kind" to it.kind, "enabled" to it.enabled,
                            "availability" to it.availability.name, "observedAt" to it.observedAt,
                            "keyConfigured" to it.keyConfigured, "credentialObservedAt" to it.credentialObservedAt,
                            "capabilities" to it.capabilities, "requestOperation" to it.requestOperation,
                            "requestOutcome" to it.requestOutcome, "httpStatus" to it.httpStatus,
                            "healthScope" to "http_response_only")
                    }
                    if (snapshot.modelRoutes.any { it.enabled && it.availability == StatusFact.UNKNOWN }) {
                        unknown.add("models.availability")
                    }
                    if (snapshot.modelRoutes.any { it.enabled && it.keyConfigured == null }) {
                        unknown.add("models.keyConfigured")
                    }
                    if (snapshot.modelRoutes.any { it.enabled && it.availability == StatusFact.STALE }) stale += "models"
                }
                "runtime" -> {
                    facts["runtime"] = mapOf("turnOwner" to snapshot.runtime.turnOwner,
                        "pendingJobs" to snapshot.runtime.pendingJobs)
                    if (snapshot.runtime.lastFailureCode != null)
                        facts["lastFailureCode"] = snapshot.runtime.lastFailureCode
                }
                "termux" -> {
                    facts["termux"] = mapOf("connection" to snapshot.termux.connection.name,
                        "port" to snapshot.termux.supervisorPort, "observedAt" to snapshot.termux.observedAt,
                        "rootfs" to snapshot.termux.rootfs, "sdkVersion" to snapshot.termux.sdkVersion,
                        "releaseProtocol" to snapshot.termux.releaseProtocol,
                        "deploymentTruncated" to snapshot.termux.deploymentTruncated)
                    snapshot.termux.runtimeModules?.let { modules ->
                        facts["dynamicModules"] = modules.map { mapOf("id" to it.id, "state" to it.state) }
                    }
                    facts["deployments"] = snapshot.termux.deployments.map {
                        mapOf("moduleId" to it.moduleId, "activeVersion" to it.active?.deploymentId,
                            "contentHash" to it.active?.contentHash, "state" to it.active?.state,
                            "observedAt" to it.observedAt, "freshness" to it.freshness.name,
                            "emergencyStopped" to it.emergencyStopped, "errorCode" to it.errorCode)
                    }
                }
                "budgets" -> facts["budgets"] = mapOf("remainingClass" to snapshot.budgets.remainingClass)
                "retrieval" -> {
                    facts["retrieval"] = mapOf(
                        "memoryAvailable" to snapshot.retrieval.memoryAvailable.name,
                        "knowledgeAvailable" to snapshot.retrieval.knowledgeAvailable.name,
                        "backlog" to snapshot.retrieval.backlog,
                        "observedAt" to snapshot.retrieval.observedAt,
                        "extractionJobs" to snapshot.retrieval.extractionJobs,
                        "extractionObservedAt" to snapshot.retrieval.extractionObservedAt)
                    facts["retrievalDomains"] = snapshot.retrieval.domains.map {
                        mapOf("domain" to it.domain, "activeVersion" to it.activeVersion,
                            "activeSpaceMatches" to it.activeSpaceMatches, "candidateState" to it.candidateState,
                            "staged" to it.staged, "total" to it.total, "backlog" to it.backlog,
                            "observedAt" to it.observedAt, "freshness" to it.freshness.name,
                            "readError" to it.readError)
                    }
                    if (snapshot.retrieval.indexVersion != null)
                        facts["indexVersion"] = snapshot.retrieval.indexVersion
                }
                "brief" -> facts["brief"] = brief(snapshot)
                "voice" -> facts["voice"] = mapOf(
                    "active" to snapshot.voice.active,
                    "state" to snapshot.voice.state)
                "resources" -> facts["resources"] = mapOf(
                    "batteryBand" to snapshot.resources.batteryBand,
                    "charging" to snapshot.resources.charging,
                    "thermalBand" to snapshot.resources.thermalBand,
                    "memoryPressure" to snapshot.resources.memoryPressure)
                "permissions" -> facts["permissions"] = snapshot.permissions.map {
                    mapOf("capability" to it.capability, "granted" to it.granted,
                        "requiresUserAction" to it.requiresUserAction)
                }
                else -> unknown.add(sec)   // 未知 section 保留 unknown
            }
        }
        // STALE 标记：超 TTL 的 section 字段
        fun age(section: String, observedAt: Long, ttl: Long) {
            if (section !in sections) return
            when (StatusProjection.freshness(observedAt, now, ttl)) {
                StatusFact.UNKNOWN -> unknown += section
                StatusFact.STALE -> stale += section
                StatusFact.LIVE -> Unit
            }
        }
        age("runtime", snapshot.runtime.observedAt, TTL_RUNTIME_MS)
        snapshot.modules.forEach { age("modules", it.observedAt, TTL_MODULE_MS) }
        age("retrieval", snapshot.retrieval.observedAt, TTL_RETRIEVAL_MS)
        age("voice", snapshot.voice.observedAt, TTL_RUNTIME_MS)
        if ("termux" in sections && snapshot.termux.connection == StatusFact.STALE) stale += "termux"
        if ("termux" in sections && snapshot.termux.connection == StatusFact.UNKNOWN) unknown += "termux"
        if ("termux" in sections) {
            if (snapshot.termux.rootfs == null) unknown += "termux.rootfs"
            if (snapshot.termux.releaseProtocol == null) unknown += "termux.releaseProtocol"
            if (snapshot.termux.sdkVersion == null) unknown += "termux.sdkVersion"
            if (snapshot.termux.runtimeModules == null) unknown += "termux.dynamicModules"
            snapshot.termux.deployments.forEach {
                when (it.freshness) {
                    StatusFact.UNKNOWN -> unknown += "termux.deployments.${it.moduleId}"
                    StatusFact.STALE -> stale += "termux.deployments.${it.moduleId}"
                    StatusFact.LIVE -> Unit
                }
            }
        }
        if ("runtime" in sections && snapshot.runtime.pendingJobs == null) unknown += "runtime.pendingJobs"
        if ("runtime" in sections && snapshot.runtime.lastFailureCode == null) unknown += "runtime.lastFailureCode"
        if ("retrieval" in sections) {
            if (snapshot.retrieval.memoryAvailable == StatusFact.UNKNOWN) unknown += "retrieval.memoryAvailable"
            if (snapshot.retrieval.knowledgeAvailable == StatusFact.UNKNOWN) unknown += "retrieval.knowledgeAvailable"
            if (snapshot.retrieval.backlog == null) unknown += "retrieval.backlog"
            if (snapshot.retrieval.indexVersion == null) unknown += "retrieval.indexVersion"
            if (snapshot.retrieval.extractionJobs == null) unknown += "retrieval.extractionJobs"
            age("retrieval", snapshot.retrieval.extractionObservedAt, TTL_RETRIEVAL_MS)
            snapshot.retrieval.domains.forEach {
                when (it.freshness) {
                    StatusFact.UNKNOWN -> unknown += "retrieval.${it.domain}"
                    StatusFact.STALE -> stale += "retrieval.${it.domain}"
                    StatusFact.LIVE -> Unit
                }
            }
        }
        if ("resources" in sections) {
            age("resources", snapshot.resources.observedAt, TTL_RUNTIME_MS)
            if (snapshot.resources.thermalBand == null) unknown += "resources.thermalBand"
        }
        if ("permissions" in sections) {
            if (snapshot.permissions.isEmpty()) unknown += "permissions"
            else snapshot.permissions.forEach { age("permissions", it.observedAt, TTL_MODULE_MS) }
        }
        return StatusProjection.page(snapshot, sections, facts, unknown, stale, limit, cursor)
    }

    private fun nextMidnight(now: Long): Long {
        val cal = java.util.Calendar.getInstance()
        cal.timeInMillis = now
        cal.set(java.util.Calendar.HOUR_OF_DAY, 0)
        cal.set(java.util.Calendar.MINUTE, 0)
        cal.set(java.util.Calendar.SECOND, 0)
        cal.set(java.util.Calendar.MILLISECOND, 0)
        cal.add(java.util.Calendar.DAY_OF_MONTH, 1)
        return cal.timeInMillis
    }
}
