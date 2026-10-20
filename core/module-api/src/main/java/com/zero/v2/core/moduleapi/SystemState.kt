package com.zero.v2.core.moduleapi

import kotlinx.coroutines.flow.StateFlow

/**
 * 系统状态快照（迭代总纲§4）——聚合各 owner 事实的只读投影。
 *
 * 设计约束：
 *   - 源模块拥有事实，repository 只投影，不建第二套可随意写的状态。
 *   - 未知必须保留 [StatusFact.UNKNOWN]，不把超时当离线、不把缓存状态当实时。
 *   - 各字段独立 observedAt；revision 为投影版本，非跨源原子事务保证。
 *   - 输出排除凭据、消息正文、文件内容、未经请求的定位和通知文本（见 [SystemStateRepository.zeroStatus] 脱敏）。
 */
data class SystemSnapshot(
    val schemaVersion: Int = 2,
    val snapshotId: String,
    val revision: Long,
    val capturedAt: Long,
    val modules: List<ModuleStatus>,
    val modelRoutes: List<ModelRouteStatus>,
    val runtime: RuntimeStatus,
    val termux: TermuxStatus,
    val budgets: BudgetStatus,
    val retrieval: RetrievalStatus,
    val voice: VoiceSessionStatus = VoiceSessionStatus(active = false, state = "IDLE", observedAt = 0L),
    val resources: ResourceStatus = ResourceStatus(),
    val permissions: List<PermissionStatus> = emptyList(),
)

/** 单模块状态投影。 */
data class ModuleStatus(
    val id: String,
    val lifecycle: String,          // ModuleLifecycle.name
    val operational: Boolean,
    val reason: String?,            // 禁用/不可用原因（不含敏感细节）
    val observedAt: Long,
    val staleAfterMs: Long,
)

/** 模型路由状态：profile 是否可用 + 能力档位（不暴露 Key/baseUrl）。 */
data class ModelRouteStatus(
    val profileId: String,
    val kind: String,               // LlmProfile.Kind.name
    val enabled: Boolean,
    val availability: StatusFact,   // UNKNOWN=Key 未配置/未探活
    val observedAt: Long,
    val keyConfigured: Boolean? = null,
    val credentialObservedAt: Long = 0L,
    val capabilities: List<String> = emptyList(),
    val requestOperation: String? = null,
    val requestOutcome: String? = null,
    val httpStatus: Int? = null,
)

/** 回合运行态：谁在占用、待执行任务数、最近故障码（不含正文）。 */
data class RuntimeStatus(
    val turnOwner: String,          // "idle" / 模块 id
    val pendingJobs: Int?,
    val lastFailureCode: String?,   // 错误类型/码，非堆栈
    val observedAt: Long,
)

/** Termux supervisor 健康投影。 */
data class TermuxStatus(
    val connection: StatusFact,     // LIVE/UNKNOWN（探活失败=UNKNOWN，不当离线断言）
    val supervisorPort: Int,
    val pid: Int?,                  // 来自 SupervisorHealth
    val deploymentState: String?,   // 简述，不含模块源码
    val observedAt: Long,
    val rootfs: Boolean? = null,
    val sdkVersion: String? = null,
    val releaseProtocol: Int? = null,
    val runtimeModules: List<DynamicRuntimeStatus>? = null,
    val deployments: List<ModuleDeploymentStatus> = emptyList(),
    val deploymentTruncated: Boolean = false,
)

data class DynamicRuntimeStatus(val id: String, val state: String)
data class ReleaseVersionStatus(val deploymentId: String, val contentHash: String, val state: String, val updatedAt: Long)
data class ModuleDeploymentStatus(
    val moduleId: String,
    val active: ReleaseVersionStatus?,
    val recent: List<ReleaseVersionStatus>,
    val observedAt: Long,
    val emergencyStopped: Boolean? = null,
    val errorCode: String? = null,
    val freshness: StatusFact = StatusFact.UNKNOWN,
)

/** 预算状态：剩余档位 + 下次重置（不暴露精确余额防推断，仅档位）。 */
data class BudgetStatus(
    val remainingClass: String,     // "ok" / "low" / "exceeded"
    val nextResetAt: Long,          // 日重置时间戳
    val observedAt: Long,
)

/** 检索域状态：记忆/知识库可用性 + 索引版本 + 积压。 */
data class RetrievalStatus(
    val memoryAvailable: StatusFact,
    val knowledgeAvailable: StatusFact,
    val indexVersion: String?,
    val backlog: Int?,
    val observedAt: Long,
    val domains: List<RetrievalDomainStatus> = emptyList(),
    val extractionJobs: Int? = null,
    val extractionObservedAt: Long = 0L,
)

/** Local index owner observation; an active version alone does not imply a current compatible index. */
data class RetrievalDomainStatus(
    val domain: String,
    val activeVersion: String?,
    val activeSpaceMatches: Boolean?,
    val candidateState: String?,
    val staged: Int?,
    val total: Int?,
    val backlog: Int?,
    val observedAt: Long,
    val freshness: StatusFact = StatusFact.UNKNOWN,
    val readError: String? = null,
)

data class ResourceStatus(
    val batteryBand: String? = null,
    val charging: Boolean? = null,
    val thermalBand: String? = null,
    val memoryPressure: Boolean? = null,
    val observedAt: Long = 0L,
)

data class PermissionStatus(
    val capability: String,
    val granted: Boolean,
    val requiresUserAction: Boolean = !granted,
    val observedAt: Long,
)

/** 语音会话状态投影（Live 实时语音；源 = LocalVoiceProvider，UI 与 LLM 共享同一投影）。 */
data class VoiceSessionStatus(
    val active: Boolean,            // 会话是否进行中（LocalVoiceProvider.isActive）
    val state: String,             // RealtimeState.name：IDLE/LISTENING/THINKING/SPEAKING/INTERRUPTED/OBSERVING_SCREEN
    val observedAt: Long,
)

/** 状态事实的三值：LIVE（实时/缓存有效）/ UNKNOWN（无法确定）/ STALE（超 TTL）。 */
enum class StatusFact { LIVE, UNKNOWN, STALE }

/**
 * zero_status 查询结果（迭代总纲§4）。
 *
 * 只读分页；返回 facts、unknown、stale、truncated 与 nextCursor。
 * 不主动触发付费模型、安装软件或唤醒重服务。
 */
data class ZeroStatusResult(
    val revision: Long,
    val capturedAt: Long,
    val facts: Map<String, Any>,        // 请求 sections 的事实（已脱敏）
    val unknown: List<String>,          // 无法确定的字段（保留 unknown，不当离线）
    val stale: List<String>,            // 超 TTL 字段
    val truncated: Boolean,
    val nextCursor: String?,
)

/**
 * 系统状态仓库 seam——聚合已有 owner 事实的只读投影。
 *
 * 实现注入各 owner（ModuleRuntimeStateReader、BudgetGuard、AgentRuntime、TermuxBridge、
 * LlmProfileStore 等），只读投影，不写第二套状态。动作执行前仍由原始 owner 实时校验，
 * 不能凭此快照执行。
 *
 * 默认 TTL 建议（可配）：资源/任务 15s，权限/模块 30s，索引 60s。
 */
interface SystemStateRepository {
    /** 当前完整快照（只读投影）。 */
    fun snapshot(): SystemSnapshot

    /** 快照响应式流——owner 状态变化（含语音会话）时即时刷新；UI 订阅驱动状态胶囊。 */
    val snapshots: StateFlow<SystemSnapshot>

    /** zero_status：按 sections 只读分页查询；sinceRevision 仅返回 revision 新于该值的变化。 */
    fun zeroStatus(sections: Set<String>, sinceRevision: Long? = null, limit: Int = 50, cursor: String? = null): ZeroStatusResult

    /** 投影修订号（每次快照变更递增）。 */
    val revision: StateFlow<Long>
}
