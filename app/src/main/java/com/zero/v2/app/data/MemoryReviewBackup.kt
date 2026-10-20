package com.zero.v2.app.data

import com.zero.v2.core.common.MemoryStatus
import com.zero.v2.core.common.MemoryType
import com.zero.v2.core.data.dao.MemoryReviewDao
import com.zero.v2.core.data.db.ZeroDatabase
import com.zero.v2.core.data.entity.MemoryConflictEntity
import com.zero.v2.core.data.entity.MemoryEntity
import com.zero.v2.core.data.entity.MemoryReviewEntity
import com.zero.v2.core.data.entity.MemorySourceEntity
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject

/** Version 3 snapshot component. Caller owns the shared Room transaction and import Mutex. */
internal object MemoryReviewBackup {
    suspend fun export(db: ZeroDatabase, root: JSONObject) {
        root.put("memoryReviews", JSONArray(db.memoryReviewDao().observe().first().map(::encode)))
        root.put("memorySources", JSONArray(db.memoryDao().allSources().map {
            JSONObject().put("summaryId", it.summaryId).put("sourceId", it.sourceId)
        }))
    }

    fun decode(root: JSONObject): List<MemoryReviewEntity> {
        if (root.optInt("version", 1) < 3) return emptyList()
        val rows = root.getJSONArray("memoryReviews")
        return (0 until rows.length()).map { index ->
            val p = rows.getJSONObject(index)
            MemoryReviewEntity(p.getString("id"), p.getString("memoryId"), p.getString("baseVersion"),
                p.getString("proposedContent"), MemoryType.valueOf(p.getString("proposedType")),
                p.getString("proposedScope"), p.getString("reason"), p.nullableString("sourceMessageId"),
                p.nullableString("sourceVersion"), p.getString("state"), p.getString("evidenceKind"),
                p.getLong("createdAt"), p.nullableLong("decidedAt"), p.nullableString("resultMemoryId"), p.optBoolean("editTimes", false),
                p.nullableLong("proposedOccurredAt"), p.nullableLong("proposedValidFrom"), p.nullableLong("proposedValidUntil"))
                .also(::validate)
        }.also { require(it.map { p -> p.id }.distinct().size == it.size) { "备份包含重复复核提案" } }
    }

    /** Install lineage before applying tombstones; otherwise a renamed descendant could revive. */
    suspend fun restoreSources(db: ZeroDatabase, root: JSONObject) {
        if (root.optInt("version", 1) < 3) return
        val values = root.getJSONArray("memorySources")
        val sources = (0 until values.length()).map {
            val value = values.getJSONObject(it)
            MemorySourceEntity(value.getString("summaryId"), value.getString("sourceId")).also { s ->
                require(s.summaryId.isNotBlank() && s.sourceId.isNotBlank() && s.summaryId != s.sourceId) {
                    "备份记忆依赖无效"
                }
            }
        }
        db.memoryDao().insertSources(sources)
        sources.map { it.sourceId }.distinct().forEach { id ->
            if (db.memoryDao().wasDeleted(id)) db.memoryExtractionJobDao().forgetById(id, System.currentTimeMillis())
        }
    }

    /** Local decisions win, including rejected/invalidated states. Do not import their old result. */
    suspend fun blockedResults(db: ZeroDatabase, proposals: List<MemoryReviewEntity>): Set<String> =
        proposals.filter { p ->
            val local = db.memoryReviewDao().get(p.id)
            (local != null && local != p) || db.chronicleSourceDao().chronicleBlocked("confirmation", p.id, null, p.memoryId)
        }.mapNotNull { it.resultMemoryId }.toSet()

    suspend fun restore(db: ZeroDatabase, proposals: List<MemoryReviewEntity>, blocked: Set<String>): Int {
        val dao = db.memoryReviewDao()
        var restored = 0
        for (p in proposals) {
            if (dao.get(p.id) != null || p.resultMemoryId in blocked || dao.deleted(p.memoryId) ||
                dao.deleted("review:${p.id}") || db.chronicleSourceDao().chronicleBlocked("confirmation", p.id, null, p.memoryId)) continue
            val base = dao.memory(p.memoryId) ?: error("复核提案缺少原记忆")
            val validBase = MemoryConflictEntity.version(base) == p.baseVersion
            if (p.state == "ACCEPTED") {
                val result = dao.memory(requireNotNull(p.resultMemoryId)) ?: error("确认审计缺少结果记忆")
                require(validBase && base.status == MemoryStatus.ARCHIVED && result.origin == "USER_CONFIRMED" &&
                    result.content == p.proposedContent && result.type == p.proposedType && result.scope == p.proposedScope &&
                    result.supersedesId == base.id && result.revision == base.revision + 1 && result.createdAt == p.decidedAt &&
                    result.sourceMessageId == null && result.sourceConversationId == null &&
                    result.occurredAt == (if (p.editTimes) p.proposedOccurredAt else base.occurredAt) &&
                    result.validFrom == (if (p.editTimes) p.proposedValidFrom else base.validFrom) &&
                    result.validUntil == (if (p.editTimes) p.proposedValidUntil else base.validUntil)) { "确认审计与记忆不一致，恢复已取消" }
                require(base.id in db.memoryDao().summarySources(result.id)) { "确认审计缺少删除依赖" }
            }
            val sourceVersion = p.sourceMessageId?.let { dao.message(it) }?.let(MemoryReviewDao::sourceVersion)
            val value = if (p.state == "PENDING" && (!validBase || base.status != MemoryStatus.ACTIVE || sourceVersion != p.sourceVersion))
                p.copy(state = "INVALIDATED", decidedAt = System.currentTimeMillis()) else p
            dao.insertProposal(value)
            restored++
        }
        return restored
    }

    suspend fun verifyNewConfirmations(db: ZeroDatabase, imported: List<MemoryEntity>) {
        for (memory in imported.filter { it.origin == "USER_CONFIRMED" }) {
            val audit = db.memoryReviewDao().get(memory.id.removePrefix("review:"))
            require(audit?.state == "ACCEPTED" && audit.resultMemoryId == memory.id &&
                audit.proposedContent == memory.content && audit.proposedType == memory.type &&
                audit.proposedScope == memory.scope) { "已确认记忆缺少匹配审计，恢复已取消" }
        }
    }

    private fun validate(p: MemoryReviewEntity) {
        require(p.memoryId.isNotBlank() && p.baseVersion.matches(Regex("[0-9a-f]{64}")) &&
            p.proposedContent.isNotBlank() && p.proposedContent.length <= 4000 &&
            p.proposedScope.isNotBlank() && p.proposedScope.length <= 100 &&
            p.reason.isNotBlank() && p.reason.length <= 1000 && p.proposedType in MemoryReviewDao.REVIEW_TYPES &&
            p.createdAt >= 0 && (p.decidedAt?.let { it >= 0 } ?: true)) { "复核提案字段无效" }
        if (p.proposedType == MemoryType.TASK) {
            val task = requireNotNull(com.zero.v2.core.common.TaskMemory.decode(p.proposedContent))
            require(!task.text.trimStart().startsWith("[zero-task:"))
        }
        com.zero.v2.core.data.entity.MemoryReviewTimes.from(p)
        require(p.editTimes || listOf(p.proposedOccurredAt, p.proposedValidFrom, p.proposedValidUntil).all { it == null })
        val id = com.zero.v2.core.data.entity.MemoryReviewTimes.identity(p.baseVersion, p.proposedContent,
            p.proposedType.name, p.proposedScope, com.zero.v2.core.data.entity.MemoryReviewTimes.from(p))
        require(p.id == id && p.state in setOf("PENDING", "ACCEPTED", "REJECTED", "INVALIDATED")) { "复核标识或状态无效" }
        require(if (p.state == "ACCEPTED") p.evidenceKind == "USER_CONFIRMATION" &&
            p.resultMemoryId == "review:${p.id}" && p.decidedAt != null
        else p.evidenceKind == "USER_REVIEW_DRAFT" && p.resultMemoryId == null &&
            (if (p.state == "PENDING") p.decidedAt == null else p.decidedAt != null)) { "复核确认审计无效" }
    }

    private fun encode(p: MemoryReviewEntity) = JSONObject().apply {
        put("id", p.id); put("memoryId", p.memoryId); put("baseVersion", p.baseVersion)
        put("proposedContent", p.proposedContent); put("proposedType", p.proposedType.name)
        put("proposedScope", p.proposedScope); put("reason", p.reason)
        put("sourceMessageId", p.sourceMessageId ?: JSONObject.NULL); put("sourceVersion", p.sourceVersion ?: JSONObject.NULL)
        put("state", p.state); put("evidenceKind", p.evidenceKind); put("createdAt", p.createdAt)
        put("editTimes", p.editTimes)
        put("proposedOccurredAt", p.proposedOccurredAt ?: JSONObject.NULL)
        put("proposedValidFrom", p.proposedValidFrom ?: JSONObject.NULL)
        put("proposedValidUntil", p.proposedValidUntil ?: JSONObject.NULL)
        put("decidedAt", p.decidedAt ?: JSONObject.NULL); put("resultMemoryId", p.resultMemoryId ?: JSONObject.NULL)
    }
    private fun JSONObject.nullableString(key: String): String? = if (isNull(key)) null else getString(key)
    private fun JSONObject.nullableLong(key: String): Long? = if (isNull(key)) null else getLong(key)
}
