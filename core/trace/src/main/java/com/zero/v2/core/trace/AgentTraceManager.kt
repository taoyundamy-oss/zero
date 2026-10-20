package com.zero.v2.core.trace

import com.zero.v2.core.data.dao.AgentTraceEventDao
import com.zero.v2.core.data.entity.AgentTraceEventEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

/**
 * AgentTrace 管理器 — 白盒可观测性核心。
 *
 * 对标 Alife 的 LogOverlay + pet.log + alife.log 三层日志体系。
 * 将所有 LLM 交互、工具调用、异常记录串联为可追溯的 timeline。
 *
 * 使用方式：
 *   - ChatBotModule: trace.startTrace() → trace.record(USER_INPUT, ...) → trace.record(LLM_OUTPUT, ...)
 *   - FunctionRegistry: trace.record(TOOL_CALL_START, ...) → trace.record(TOOL_CALL_RESULT, ...)
 *   - MemoryModule: trace.record(COMPRESSION, ...)
 *   - 任何 catch 块: trace.record(ERROR, ...)
 *
 * v2.2: 所有采集点通过 traceId 串联全链路。
 * v3.7: record() 异步持久化到 Room（agent_trace_events 表，D3 独立于 Message）。
 *       内存环形缓冲（100 条 trace）语义不变；Room 为追加型历史，7 天保留期，
 *       每 200 条写入做一次清理。持久化失败静默吞掉（Trace 永不阻断业务）。
 */
@Singleton
class AgentTraceManager @Inject constructor(
    private val traceEventDao: AgentTraceEventDao
) {

    /** 当前活跃的 traceId -> trace 记录列表 */
    private val traces = ConcurrentHashMap<String, MutableList<AgentTrace>>()

    /** 内存环形缓冲区上限（保留最近 100 条 trace） */
    private val maxTraces = 100

    /** 持久化作用域 — fire-and-forget，失败不外泄 */
    private val persistScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 写入计数 — 周期性触发保留期清理 */
    private val persistCounter = AtomicLong(0)

    private companion object {
        /** Room 保留期：7 天 */
        const val RETENTION_MS = 7L * 24 * 3600_000
        /** 每多少条写入触发一次清理检查 */
        const val PRUNE_EVERY = 200L
    }

    /**
     * 开始一次新的追踪会话。
     *
     * @return traceId（UUID 前 8 位）
     */
    fun startTrace(): String {
        val traceId = UUID.randomUUID().toString().take(8)
        traces[traceId] = mutableListOf()
        // 环形缓冲区清理
        if (traces.size > maxTraces) {
            val oldest = traces.keys.firstOrNull()
            if (oldest != null) traces.remove(oldest)
        }
        return traceId
    }

    /**
     * 记录一条追踪事件。
     *
     * @param traceId 追踪 ID
     * @param nodeType 节点类型
     * @param sourceModule 来源模块 ID
     * @param summary 一行摘要
     * @param detail 详细信息（可选）
     * @param tokensUsed Token 消耗（可选）
     * @param durationMs 耗时（可选）
     * @param parentTraceId 父追踪 ID（可选，嵌套调用时使用）
     * @return 记录的追踪序号
     */
    fun record(
        traceId: String,
        nodeType: NodeType,
        sourceModule: String,
        summary: String,
        detail: String? = null,
        tokensUsed: Long? = null,
        durationMs: Long? = null,
        parentTraceId: String? = null
    ): Int {
        return record(AgentTraceEvent(
            traceId = traceId,
            nodeType = nodeType,
            sourceModule = sourceModule,
            summary = summary,
            detail = detail,
            tokensUsed = tokensUsed,
            durationMs = durationMs,
            parentTraceId = parentTraceId
        ))
    }

    /** 结构化事件入口；调用方不需要知道 Room 存储格式。 */
    fun record(event: AgentTraceEvent): Int {
        val traceId = event.traceId
        val list = traces.getOrPut(traceId) { mutableListOf() }
        val entry = AgentTrace(
            traceId = traceId,
            sequence = list.size + 1,
            nodeType = event.nodeType,
            sourceModule = event.sourceModule,
            summary = event.summary,
            detail = event.detail,
            tokensUsed = event.tokensUsed,
            durationMs = event.durationMs,
            parentTraceId = event.parentTraceId,
            callId = event.callId,
            functionName = event.functionName
        )
        list.add(entry)
        persistAsync(entry)
        return entry.sequence
    }

    /** v3.7: 异步落库（fire-and-forget，任何失败静默吞掉） */
    private fun persistAsync(entry: AgentTrace) {
        try {
            persistScope.launch {
                try {
                    traceEventDao.insert(AgentTraceEventEntity(
                        traceId = entry.traceId,
                        sequence = entry.sequence,
                        timestamp = entry.timestamp,
                        nodeType = entry.nodeType.name,
                        sourceModule = entry.sourceModule,
                        summary = entry.summary,
                        detail = entry.detail,
                        tokensUsed = entry.tokensUsed,
                        durationMs = entry.durationMs,
                        parentTraceId = entry.parentTraceId,
                        callId = entry.callId,
                        functionName = entry.functionName
                    ))
                    if (persistCounter.incrementAndGet() % PRUNE_EVERY == 0L) {
                        traceEventDao.deleteOlderThan(System.currentTimeMillis() - RETENTION_MS)
                    }
                } catch (_: Exception) {
                    // Room 不可用时 Trace 退化为纯内存模式
                }
            }
        } catch (_: Exception) {
            // scope 已取消等极端情况 — 忽略
        }
    }

    /**
     * 获取指定 traceId 的所有记录（按序号排序）。
     */
    fun getTrace(traceId: String): List<AgentTrace> {
        return traces[traceId]?.sortedBy { it.sequence } ?: emptyList()
    }

    /**
     * 获取所有活跃的 traceId 列表。
     */
    fun getAllTraceIds(): List<String> {
        return traces.keys.toList()
    }

    /**
     * 获取指定 traceId 的统计摘要。
     */
    fun getTraceSummary(traceId: String): TraceSummary? {
        val trace = traces[traceId] ?: return null
        val totalTokens = trace.mapNotNull { it.tokensUsed }.sum()
        val totalDuration = trace.mapNotNull { it.durationMs }.sum()
        val errorCount = trace.count { it.nodeType == NodeType.ERROR }
        val firstEvent = trace.minByOrNull { it.timestamp }
        val lastEvent = trace.maxByOrNull { it.timestamp }
        return TraceSummary(
            traceId = traceId,
            eventCount = trace.size,
            totalTokensUsed = totalTokens,
            totalDurationMs = totalDuration,
            errorCount = errorCount,
            startedAt = firstEvent?.timestamp ?: 0,
            endedAt = lastEvent?.timestamp ?: 0
        )
    }

    /**
     * 结束追踪（可选 — 通常在对话完成/自动化完成后调用）。
     * 保留内存记录供 UI 查询；v3.7 起事件已随 record() 实时落库（Room）。
     */
    fun endTrace(traceId: String) {
        // 内存记录保留供 UI 查询；持久化已在 record() 内完成
    }
}

/**
 * Trace 统计摘要。
 */
data class TraceSummary(
    val traceId: String,
    val eventCount: Int,
    val totalTokensUsed: Long,
    val totalDurationMs: Long,
    val errorCount: Int,
    val startedAt: Long,
    val endedAt: Long
)
