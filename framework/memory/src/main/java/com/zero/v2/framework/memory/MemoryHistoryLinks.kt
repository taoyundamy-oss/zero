package com.zero.v2.framework.memory

import com.zero.v2.core.data.dao.MemoryDao
import com.zero.v2.core.data.entity.MemoryEntity
import org.json.JSONArray
import org.json.JSONObject

/** Bounded direct edges only. Following an ID requires a fresh guarded read, never recursive expansion. */
internal class MemoryHistoryLinks(private val dao: MemoryDao) {
    suspend fun attach(payload: String, memory: MemoryEntity): String {
        val next = dao.replacementsOf(memory.id, memory.scope)
        val shown = next.take(16).filterNot { dao.wasDeleted(it.id) }
        val previous = memory.supersedesId?.let { dao.getById(it) }
            ?.takeIf { it.scope == memory.scope && !dao.wasDeleted(it.id) }
        val now = System.currentTimeMillis()
        val historical = memory.status != com.zero.v2.core.common.MemoryStatus.ACTIVE ||
            (memory.validUntil?.let { it <= now } ?: false) || (memory.validFrom?.let { it > now } ?: false)
        val data = JSONObject(payload)
        if (historical) data.put("usable_as_current_fact", false)
        return data.put("historical_only", historical || data.optString("currentness") == "HISTORY_DIAGNOSTIC_ONLY").put("replaces_id", previous?.id ?: JSONObject.NULL)
            .put("superseded_by", JSONArray(shown.map { it.id })).put("lineage_truncated", next.size > 16)
            .put("expired", memory.validUntil?.let { it <= now } ?: false)
            .put("not_yet_valid", memory.validFrom?.let { it > now } ?: false)
            .put("status", memory.status.name).put("occurredAt", memory.occurredAt ?: JSONObject.NULL)
            .put("validFrom", memory.validFrom ?: JSONObject.NULL).put("validUntil", memory.validUntil ?: JSONObject.NULL)
            .put("lineage_notice", "仅版本关系；新记录不自动优先，明确更正仍须版本/来源核对，历史不恢复为当前事实。")
            .toString()
    }
}
