package com.zero.v2.core.data.dao

import androidx.room.*
import com.zero.v2.core.common.MemoryStatus
import com.zero.v2.core.common.MemoryType
import com.zero.v2.core.data.entity.*
import kotlinx.coroutines.flow.Flow
import java.security.MessageDigest

@Dao
abstract class MemoryReviewDao : ChronicleSourceWriter {
    @Query("SELECT * FROM memory_reviews ORDER BY createdAt DESC, id")
    abstract fun observe(): Flow<List<MemoryReviewEntity>>
    @Query("SELECT * FROM memory_reviews WHERE id = :id")
    abstract suspend fun get(id: String): MemoryReviewEntity?
    @Query("SELECT * FROM memories WHERE id = :id")
    abstract suspend fun memory(id: String): MemoryEntity?
    @Query("SELECT * FROM messages WHERE id = :id")
    abstract suspend fun message(id: String): MessageEntity?
    @Query("SELECT EXISTS(SELECT 1 FROM memory_deletions WHERE id = :id)")
    abstract suspend fun deleted(id: String): Boolean
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun insertProposal(value: MemoryReviewEntity): Long
    @Insert
    abstract suspend fun insertMemory(value: MemoryEntity)
    @Insert
    abstract suspend fun insertSource(value: MemorySourceEntity)
    @Query("UPDATE memories SET status = 'ARCHIVED' WHERE id = :id")
    abstract suspend fun archive(id: String)
    @Query("WITH RECURSIVE affected(id) AS (SELECT summaryId FROM memory_sources WHERE sourceId = :id UNION " +
        "SELECT s.summaryId FROM memory_sources s JOIN affected a ON s.sourceId = a.id) " +
        "UPDATE memories SET status = 'ARCHIVED' WHERE id IN (SELECT id FROM affected)")
    abstract suspend fun archiveDerived(id: String)
    @Query("UPDATE memory_reviews SET state = :state, decidedAt = :now, resultMemoryId = :result, " +
        "evidenceKind = :evidence WHERE id = :id AND state = 'PENDING'")
    abstract suspend fun finish(id: String, state: String, now: Long, result: String?, evidence: String)

    /** Deterministic ID suppresses duplicate/rejected proposals for the same revision and decision. */
    @Transaction
    open suspend fun propose(expected: MemoryEntity, content: String, type: MemoryType,
        scope: String, reason: String, now: Long, times: MemoryReviewTimes? = null): String? {
        require(content.isNotBlank() && content.length <= 4000 && scope.isNotBlank() && scope.length <= 100)
        require(reason.isNotBlank() && reason.length <= 1000)
        require(type in REVIEW_TYPES)
        if (type == MemoryType.TASK) {
            val task = requireNotNull(com.zero.v2.core.common.TaskMemory.decode(content)) { "任务字段不完整" }
            require(!task.text.trimStart().startsWith("[zero-task:")) { "任务正文不能嵌套内部协议" }
        }
        val current = memory(expected.id) ?: return null
        if (current.status != MemoryStatus.ACTIVE || deleted(current.id) ||
            MemoryConflictEntity.version(current) != MemoryConflictEntity.version(expected)) return null
        val source = current.sourceMessageId?.let { message(it) }
        val version = MemoryConflictEntity.version(current)
        val id = MemoryReviewTimes.identity(version, content, type.name, scope, times)
        if (chronicleBlocked("confirmation", id, null, current.id)) return null
        insertProposal(MemoryReviewEntity(id, current.id, version, content, type, scope, reason,
            current.sourceMessageId, source?.let(::sourceVersion), createdAt = now, editTimes = times != null,
            proposedOccurredAt = times?.occurredAt, proposedValidFrom = times?.validFrom, proposedValidUntil = times?.validUntil))
        return id
    }

    /** Only an explicit UI confirmation reaches here; stale inputs cannot resurrect history. */
    @Transaction
    open suspend fun decide(id: String, accept: Boolean, now: Long): Boolean {
        val p = get(id)?.takeIf { it.state == "PENDING" } ?: return false
        val m = memory(p.memoryId)
        val source = p.sourceMessageId?.let { message(it) }
        if (m == null || m.status != MemoryStatus.ACTIVE || deleted(p.memoryId) ||
            chronicleBlocked("confirmation", p.id, null, p.memoryId) ||
            MemoryConflictEntity.version(m) != p.baseVersion || source?.let(::sourceVersion) != p.sourceVersion) {
            finish(id, "INVALIDATED", now, null, p.evidenceKind)
            return false
        }
        if (!accept) {
            finish(id, "REJECTED", now, null, p.evidenceKind)
            return true
        }
        val times = MemoryReviewTimes.from(p)
        val resultId = "review:$id"
        if (deleted(resultId) || memory(resultId) != null) {
            finish(id, "INVALIDATED", now, null, p.evidenceKind)
            return false
        }
        // Confirmation is new evidence, not a claim that a legacy quote has been recovered.
        insertMemory(m.copy(id = resultId, content = p.proposedContent, type = p.proposedType,
            tier = if (p.proposedType == MemoryType.EVENT) 1 else 2,
            scope = p.proposedScope, origin = "USER_CONFIRMED", sourceMessageId = null,
            sourceConversationId = null, embedding = null, embedModel = "", keywords = null,
            supersedesId = m.id, revision = m.revision + 1, createdAt = now,
            accessCount = 0, reinforcementCount = 0, lastAccessedAt = now,
            occurredAt = if (times != null) times.occurredAt else m.occurredAt,
            validFrom = if (times != null) times.validFrom else m.validFrom,
            validUntil = if (times != null) times.validUntil else m.validUntil))
        archiveDerived(m.id)
        insertSource(MemorySourceEntity(resultId, m.id))
        archive(m.id)
        finish(id, "ACCEPTED", now, resultId, "USER_CONFIRMATION")
        retainSource(ChronicleSourceEntity.confirmation(checkNotNull(get(id)), now))
        return true
    }

    companion object {
        val REVIEW_TYPES = setOf(MemoryType.FACT, MemoryType.EVENT, MemoryType.PREFERENCE, MemoryType.DIRECTIVE, MemoryType.RELATIONSHIP, MemoryType.TASK)
        fun sourceVersion(m: MessageEntity): String = hash(listOf(m.id, m.conversationId, m.role,
            m.content.orEmpty(), m.source.orEmpty(), m.systemSource.orEmpty(), m.triggerId.orEmpty())
            .joinToString("") { "${it.length}:$it" })
        private fun hash(value: String) = MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }
}
