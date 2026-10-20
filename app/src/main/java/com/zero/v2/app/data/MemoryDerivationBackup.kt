package com.zero.v2.app.data

import com.zero.v2.core.common.MemoryType
import com.zero.v2.core.data.db.ZeroDatabase
import com.zero.v2.core.data.entity.*
import org.json.JSONArray
import org.json.JSONObject

/** v5 stores immutable inputs and queue state, never the rebuildable current-view cache. */
internal object MemoryDerivationBackup {
    suspend fun export(db: ZeroDatabase, root: JSONObject) {
        root.put("memoryDerivations", JSONArray(db.memoryDerivationDao().all().map { d ->
            JSONObject().put("id", d.id).put("sourceId", d.sourceId).put("slotId", d.slotId)
                .put("content", d.content).put("quote", d.quote).put("type", d.type.name)
                .put("basis", d.basis).put("scope", d.scope).put("claimKey", d.claimKey)
                .put("importance", d.importance.toDouble()).put("createdAt", d.createdAt)
                .put("occurredAt", d.occurredAt ?: JSONObject.NULL).put("validFrom", d.validFrom ?: JSONObject.NULL)
                .put("validUntil", d.validUntil ?: JSONObject.NULL).put("state", d.state)
                .put("appliedAt", d.appliedAt ?: JSONObject.NULL)
        }))
    }

    fun decode(root: JSONObject): List<MemoryDerivationEntity> {
        if (root.optInt("version", 1) < 5) return emptyList()
        val rows = root.getJSONArray("memoryDerivations")
        return (0 until rows.length()).map { i ->
            val p = rows.getJSONObject(i)
            fun time(k: String) = if (p.isNull(k)) null else p.getLong(k)
            MemoryDerivationEntity(p.getString("id"), p.getString("sourceId"), p.getString("slotId"),
                p.getString("content"), p.getString("quote"), MemoryType.valueOf(p.getString("type")),
                p.getString("basis"), p.getString("scope"), p.getString("claimKey"), p.getDouble("importance").toFloat(),
                time("occurredAt"), time("validFrom"), time("validUntil"), p.getLong("createdAt"),
                p.getString("state"), time("appliedAt")).also { it.validate() }
        }.also { require(it.map { d -> d.id }.distinct().size == it.size) { "重复派生绑定" } }
    }

    /** A result tombstone also removes its immutable source, including confirmation sources. */
    suspend fun propagateDeletions(db: ZeroDatabase, rows: List<MemoryDerivationEntity>, sources: List<ChronicleSourceEntity>) {
        for (d in rows) if (db.memoryDao().wasDeleted(d.memoryId)) {
            val s = db.chronicleSourceDao().chronicle(d.sourceId) ?: sources.find { it.id == d.sourceId } ?: continue
            db.chronicleSourceDao().redact(ChronicleDeletionEntity(s.kind, s.anchorId, System.currentTimeMillis()))
        }
    }

    suspend fun restore(db: ZeroDatabase, rows: List<MemoryDerivationEntity>): List<MemoryDerivationEntity> {
        val added = mutableListOf<MemoryDerivationEntity>()
        for (d in rows) {
            if (db.memoryDao().wasDeleted(d.memoryId)) continue
            val s = db.chronicleSourceDao().chronicle(d.sourceId) ?: continue
            if (db.chronicleSourceDao().chronicleBlocked(s.kind, s.anchorId, s.conversationId, s.parentMemoryId)) continue
            require(d.boundTo(s)) { "派生来源版本不匹配" }
            val old = db.memoryDerivationDao().get(d.id)
            if (old != null) {
                require(old.copy(createdAt = d.createdAt, state = d.state, appliedAt = d.appliedAt) == d) { "派生身份冲突" }
                continue // Local lifecycle state wins, including STALE.
            }
            db.memoryDerivationDao().insert(d)
            db.memoryDerivationDao().clearView(d.slotId)
            added += d
        }
        return added
    }

    suspend fun allowsMemory(db: ZeroDatabase, m: MemoryEntity): Boolean {
        if (m.origin != "MODEL_CLASSIFIED") return !m.id.startsWith("derived:")
        val d = db.memoryDerivationDao().get(m.id.removePrefix("derived:")) ?: return false
        val s = db.chronicleSourceDao().chronicle(d.sourceId) ?: return false
        return d.memoryId == m.id && d.state == "APPLIED" && d.boundTo(s) && d.matches(m, s)
    }

    suspend fun verify(db: ZeroDatabase, added: List<MemoryDerivationEntity>) {
        for (d in added.filter { it.state == "APPLIED" }) {
            val s = requireNotNull(db.chronicleSourceDao().chronicle(d.sourceId))
            val m = requireNotNull(db.memoryDao().getById(d.memoryId)) { "缺失已派生记忆" }
            require(d.matches(m, s)) { "已派生记忆与绑定不一致" }
        }
    }
}
