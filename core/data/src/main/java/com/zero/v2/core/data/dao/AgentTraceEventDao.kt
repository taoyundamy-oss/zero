package com.zero.v2.core.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.zero.v2.core.data.entity.AgentTraceEventEntity

/**
 * AgentTrace 事件 DAO — Step 3.7。
 */
@Dao
interface AgentTraceEventDao {

    @Insert
    suspend fun insert(event: AgentTraceEventEntity)

    /** 指定 trace 的全部事件（按序号） */
    @Query("SELECT * FROM agent_trace_events WHERE traceId = :traceId ORDER BY sequence ASC")
    suspend fun getByTraceId(traceId: String): List<AgentTraceEventEntity>

    /** 按结构化工具调用 ID 查询，历史 NULL 记录自然不匹配。 */
    @Query("SELECT * FROM agent_trace_events WHERE callId = :callId ORDER BY timestamp ASC, sequence ASC")
    suspend fun getByCallId(callId: String): List<AgentTraceEventEntity>

    /** 最近的 trace 列表（按最后事件时间倒序） */
    @Query(
        """
        SELECT traceId, MAX(timestamp) AS lastTime, COUNT(*) AS eventCount
        FROM agent_trace_events
        GROUP BY traceId
        ORDER BY lastTime DESC
        LIMIT :limit
        """
    )
    suspend fun getRecentTraces(limit: Int): List<RecentTraceRow>

    /** 保留期清理（返回删除行数） */
    @Query("DELETE FROM agent_trace_events WHERE timestamp < :before")
    suspend fun deleteOlderThan(before: Long): Int

    @Query("SELECT COUNT(*) FROM agent_trace_events")
    suspend fun count(): Long
}

/** getRecentTraces 的投影行 */
data class RecentTraceRow(
    val traceId: String,
    val lastTime: Long,
    val eventCount: Int
)
