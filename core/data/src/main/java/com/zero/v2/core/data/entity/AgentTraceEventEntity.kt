package com.zero.v2.core.data.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * AgentTrace 事件持久化实体 — Step 3.7（D3: Trace 独立表，不入 Message 表）。
 *
 * AgentTraceManager.record() 同步异步落库，重启后运行日志不丢。
 * 内存环形缓冲（100 条 trace）语义不变，Room 为追加型历史。
 */
@Entity(
    tableName = "agent_trace_events",
    indices = [Index("traceId"), Index("timestamp"), Index("callId")]
)
data class AgentTraceEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val traceId: String,
    val sequence: Int,
    val timestamp: Long,
    val nodeType: String,
    val sourceModule: String,
    val summary: String,
    val detail: String? = null,
    val tokensUsed: Long? = null,
    val durationMs: Long? = null,
    val parentTraceId: String? = null,
    val callId: String? = null,
    val functionName: String? = null
)
