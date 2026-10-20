package com.zero.v2.app.ui.log

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zero.v2.core.data.dao.AgentTraceEventDao
import com.zero.v2.core.data.entity.AgentTraceEventEntity
import com.zero.v2.core.trace.AgentTrace
import com.zero.v2.core.trace.AgentTraceManager
import com.zero.v2.core.trace.NodeType
import com.zero.v2.core.trace.TraceSummary
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext
import javax.inject.Inject

data class RunLogState(
    val traceIds: List<String> = emptyList(),
    val traces: Map<String, List<AgentTrace>> = emptyMap(),
    val summaries: Map<String, TraceSummary> = emptyMap(),
    /** 来自 Room 的历史 trace（重启前 / 已被内存环形缓冲淘汰） */
    val historyIds: Set<String> = emptySet(),
    val loading: Boolean = false,
    val historyError: String? = null,
)

/**
 * 运行日志 ViewModel — v3.8。
 *
 * 数据源合并: 内存（当前会话，实时）+ Room agent_trace_events（历史，Step 3.7 持久化）。
 * 同一 traceId 以内存版本优先（含实时增量）。Room 读取失败时保留已知历史并显示重试反馈。
 */
@HiltViewModel
class RunLogViewModel @Inject constructor(
    private val traceManager: AgentTraceManager,
    private val traceEventDao: AgentTraceEventDao
) : ViewModel() {

    private val _state = MutableStateFlow(RunLogState())
    val state: StateFlow<RunLogState> = _state.asStateFlow()
    private var reloadJob: Job? = null
    private var reloadGeneration = 0L

    init { reload() }

    fun reload() {
        reloadJob?.cancel()
        val generation = ++reloadGeneration
        _state.value = _state.value.copy(loading = true)
        reloadJob = viewModelScope.launch {
            try {
                // 1) 内存 trace（当前会话）
                val memIds = traceManager.getAllTraceIds()
                val memTraces = memIds.associateWith { traceManager.getTrace(it) }
                val memSummaries = memIds.mapNotNull { id ->
                    traceManager.getTraceSummary(id)?.let { id to it }
                }.toMap()

                // 2) Room 历史（最近 30 组，剔除已在内存的）
                var historyError: String? = null
                val history: Map<String, List<AgentTrace>> = try {
                    traceEventDao.getRecentTraces(30)
                        .map { it.traceId }
                        .filter { it !in memTraces }
                        .mapNotNull { id ->
                            val events = traceEventDao.getByTraceId(id).map { it.toAgentTrace() }
                            if (events.isEmpty()) null else id to events
                        }
                        .toMap()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    historyError = "历史运行记录读取失败，已保留上次记录，请重试"
                    _state.value.traces.filterKeys { it in _state.value.historyIds && it !in memTraces }
                }

                val histSummaries = history.mapValues { (_, events) -> summarize(events) }

                coroutineContext.ensureActive()
                if (generation != reloadGeneration) return@launch
                _state.value = RunLogState(
                    traceIds = memIds + history.keys,
                    traces = memTraces + history,
                    summaries = memSummaries + histSummaries,
                    historyIds = history.keys,
                    historyError = historyError,
                )
            } finally {
                if (generation == reloadGeneration) _state.value = _state.value.copy(loading = false)
            }
        }
    }

    private fun summarize(events: List<AgentTrace>): TraceSummary = TraceSummary(
        traceId = events.firstOrNull()?.traceId ?: "",
        eventCount = events.size,
        totalTokensUsed = events.mapNotNull { it.tokensUsed }.sum(),
        totalDurationMs = events.mapNotNull { it.durationMs }.sum(),
        errorCount = events.count { it.nodeType == NodeType.ERROR },
        startedAt = events.minOfOrNull { it.timestamp } ?: 0,
        endedAt = events.maxOfOrNull { it.timestamp } ?: 0
    )
}

/** Room 实体 → UI 模型（nodeType 解析失败降级 SYSTEM_EVENT） */
private fun AgentTraceEventEntity.toAgentTrace(): AgentTrace = AgentTrace(
    traceId = traceId,
    sequence = sequence,
    timestamp = timestamp,
    nodeType = try { NodeType.valueOf(nodeType) } catch (_: Exception) { NodeType.SYSTEM_EVENT },
    sourceModule = sourceModule,
    summary = summary,
    detail = detail,
    tokensUsed = tokensUsed,
    durationMs = durationMs,
    parentTraceId = parentTraceId,
    callId = callId,
    functionName = functionName
)
