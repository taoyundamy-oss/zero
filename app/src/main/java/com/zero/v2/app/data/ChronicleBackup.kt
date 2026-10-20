package com.zero.v2.app.data

import com.zero.v2.core.data.db.ZeroDatabase
import com.zero.v2.core.data.entity.ChronicleDeletionEntity
import com.zero.v2.core.data.entity.ChronicleSourceEntity
import org.json.JSONArray
import org.json.JSONObject

/** Explicit v4 component. Caller holds import Mutex and the shared Room snapshot transaction. */
internal object ChronicleBackup {
    data class Snapshot(val sources: List<ChronicleSourceEntity>, val deletions: List<ChronicleDeletionEntity>)

    suspend fun export(db: ZeroDatabase, root: JSONObject) {
        root.put("chronicleSources", JSONArray(db.chronicleSourceDao().all().map { s ->
            JSONObject().put("id", s.id).put("kind", s.kind).put("anchorId", s.anchorId)
                .put("conversationId", s.conversationId ?: JSONObject.NULL)
                .put("parentMemoryId", s.parentMemoryId ?: JSONObject.NULL).put("body", s.body)
                .put("provenance", s.provenance).put("recordedAt", s.recordedAt).put("capturedAt", s.capturedAt)
        }))
        root.put("chronicleDeletions", JSONArray(db.chronicleSourceDao().deletions().map { d ->
            JSONObject().put("kind", d.kind).put("anchorId", d.anchorId).put("deletedAt", d.deletedAt)
        }))
    }

    fun decode(root: JSONObject): Snapshot {
        if (root.optInt("version", 1) < 4) return Snapshot(emptyList(), emptyList())
        val sources = root.getJSONArray("chronicleSources").objects().map { p ->
            ChronicleSourceEntity(p.getString("id"), p.getString("kind"), p.getString("anchorId"),
                p.nullable("conversationId"), p.nullable("parentMemoryId"), p.getString("body"),
                p.getString("provenance"), p.getLong("recordedAt"), p.getLong("capturedAt")).also { it.validate() }
        }
        val deletions = root.getJSONArray("chronicleDeletions").objects().map { p ->
            ChronicleDeletionEntity(p.getString("kind"), p.getString("anchorId"), p.getLong("deletedAt"))
                .also { it.validate() }
        }
        require(sources.map { it.id }.distinct().size == sources.size) { "重复编年来源" }
        require(deletions.map { it.kind to it.anchorId }.distinct().size == deletions.size) { "重复来源删除标记" }
        return Snapshot(sources, deletions)
    }

    suspend fun restoreDeletions(db: ZeroDatabase, snapshot: Snapshot) {
        snapshot.deletions.forEach { db.chronicleSourceDao().redact(it) }
    }

    /** Even an old deletion with no surviving local source pointer wins against an older backup. */
    suspend fun propagateMemoryDeletions(db: ZeroDatabase, root: JSONObject) {
        val dao = db.chronicleSourceDao()
        val now = System.currentTimeMillis()
        for (m in root.optJSONArray("memories").legacyObjects()) {
            if (db.memoryDao().wasDeleted(m.getString("id"))) {
                m.nullable("sourceMessageId")?.let { dao.redact(ChronicleDeletionEntity("message", it, now)) }
            }
        }
        for (p in root.optJSONArray("memoryReviews").objects()) {
            if (db.memoryDao().wasDeleted(p.getString("memoryId")) ||
                p.nullable("resultMemoryId")?.let { db.memoryDao().wasDeleted(it) } == true) {
                dao.redact(ChronicleDeletionEntity("confirmation", p.getString("id"), now))
            }
        }
    }

    suspend fun restoreSources(db: ZeroDatabase, snapshot: Snapshot): Int {
        var restored = 0
        for (source in snapshot.sources) if (db.chronicleSourceDao().retainSource(source)) restored++
        return restored
    }

    private fun JSONArray?.objects(): List<JSONObject> = this?.let { a ->
        (0 until a.length()).map { a.getJSONObject(it) }
    }.orEmpty()
    private fun JSONArray?.legacyObjects(): List<JSONObject> = this?.let { a ->
        (0 until a.length()).mapNotNull { a.optJSONObject(it)?.takeIf { row -> row.optString("id").isNotBlank() } }
    }.orEmpty()
    private fun JSONObject.nullable(key: String): String? = if (!has(key) || isNull(key)) null else getString(key)
}
