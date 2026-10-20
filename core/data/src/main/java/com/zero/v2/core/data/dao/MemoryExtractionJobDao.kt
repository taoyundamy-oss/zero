package com.zero.v2.core.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.zero.v2.core.data.entity.MemoryEntity
import com.zero.v2.core.data.entity.MemoryDeletionEntity
import com.zero.v2.core.data.entity.GraphMemoryEvidence
import com.zero.v2.core.data.entity.MemoryExtractionJobEntity

/**
 * 记忆提取作业 outbox DAO（R1）。
 * 唯一键 IGNORE 幂等插入；查待处理按退避时间门控；失败 attempts 递增；完结有界清理。
 */
@Dao
interface MemoryExtractionJobDao {
    @Query("UPDATE memory_extraction_jobs SET state = 'SUPERSEDED', last_error = 'main_dialogue_classification_only', " +
        "updated_at = :now WHERE source_type = 'USER_FACT' AND state IN ('PENDING','RUNNING')")
    suspend fun retireUserFactSources(now: Long): Int

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun recordDeletion(value: MemoryDeletionEntity)

    @Query("SELECT * FROM memory_deletions ORDER BY id")
    suspend fun deletions(): List<MemoryDeletionEntity>

    @Query("SELECT EXISTS(SELECT 1 FROM memory_deletions WHERE id = :id)")
    suspend fun wasDeleted(id: String): Boolean

    @Query("SELECT EXISTS(SELECT 1 FROM chronicle_deletions WHERE kind IN ('message','transcript') AND anchorId = :id)")
    suspend fun sourceRedacted(id: String): Boolean

    @Query("SELECT EXISTS(SELECT 1 FROM chronicle_deletions WHERE kind = 'conversation' AND anchorId = :id)")
    suspend fun conversationRedacted(id: String): Boolean

    @Query("SELECT * FROM memories WHERE id = :id")
    suspend fun memoryForDeletion(id: String): MemoryEntity?

    @Query("DELETE FROM memories WHERE id = :id")
    suspend fun deleteMemoryById(id: String): Int

    @Query("DELETE FROM memory_reviews WHERE memoryId = :id OR resultMemoryId = :id")
    suspend fun deleteReviewCopies(id: String)

    @Query("UPDATE memory_extraction_jobs SET state = 'SUPERSEDED', updated_at = :now " +
        "WHERE conversation_id = :conversationId AND source_message_id = :messageId " +
        "AND state IN ('PENDING','RUNNING')")
    suspend fun supersedeSourceMessage(conversationId: String, messageId: String, now: Long): Int

    @Transaction
    suspend fun forgetById(id: String, now: Long): Int {
        val affected = derivedMemoryIds(id)
        return affected.sumOf { forgetSingleById(it, now) }
    }

    @Query("WITH RECURSIVE affected(id) AS (SELECT :id UNION SELECT s.summaryId FROM memory_sources s JOIN affected a ON s.sourceId = a.id) SELECT id FROM affected")
    suspend fun derivedMemoryIds(id: String): List<String>

    /** Called inside forgetById's transaction, including all derived summaries. */
    suspend fun forgetSingleById(id: String, now: Long): Int {
        require(id.isNotBlank())
        recordDeletion(MemoryDeletionEntity(id, now))
        val memory = memoryForDeletion(id)
        val source = id.removePrefix("user-fact:")
        if (id.startsWith("user-fact:") && source.substringAfterLast(':').matches(Regex("[0-9a-f]{16}")) &&
            source.contains(':') && !memory?.sourceConversationId.isNullOrBlank()) {
            supersedeSourceMessage(memory!!.sourceConversationId!!, source.substringBeforeLast(':'), now)
        }
        val prefix = GraphMemoryEvidence.sourcePrefix(id)
        deleteGraphAttributesForSource(prefix)
        deleteGraphRelationshipsForSource(prefix)
        deleteOwnedOrphanEntities()
        deleteMemoryVectorCopies(id)
        deleteGraphJobsForSource(id)
        deleteReviewCopies(id)
        return deleteMemoryById(id)
    }

    @Query("SELECT * FROM memory_extraction_jobs WHERE id IN (:ids)")
    suspend fun getByIds(ids: List<String>): List<MemoryExtractionJobEntity>

    @Insert
    suspend fun insertLiveMemories(memories: List<MemoryEntity>)

    /** Commit only the exact input snapshot. A deletion or competing completion invalidates it. */
    @Transaction
    suspend fun completeBatch(jobs: List<MemoryExtractionJobEntity>, memories: List<MemoryEntity>, now: Long): Boolean {
        if (jobs.isEmpty()) return false
        val current = getByIds(jobs.map { it.id }).associateBy { it.id }
        if (jobs.any { current[it.id] != it || it.state != MemoryExtractionJobEntity.STATE_PENDING }) return false
        insertLiveMemories(memories.filterNot {
            wasDeleted(it.id) || it.sourceMessageId?.let { id -> sourceRedacted(id) } == true ||
                it.sourceConversationId?.let { id -> conversationRedacted(id) } == true
        })
        jobs.forEach { markSucceeded(it.id, now) }
        return true
    }

    @Query("UPDATE memory_extraction_jobs SET state = 'SUPERSEDED', updated_at = :now WHERE state IN ('PENDING', 'RUNNING') AND instr(source_digest, :keyword) > 0")
    suspend fun supersedeMatching(keyword: String, now: Long): Int

    @Query("DELETE FROM memories WHERE instr(content, :keyword) > 0 OR instr(COALESCE(keywords, ''), :keyword) > 0")
    suspend fun deleteMatchingMemories(keyword: String): Int

    @Query("SELECT id FROM memories WHERE (instr(content, :keyword) > 0 OR " +
        "instr(COALESCE(keywords, ''), :keyword) > 0) AND (:after IS NULL OR id > :after) ORDER BY id LIMIT 128")
    suspend fun matchingMemoryIds(keyword: String, after: String?): List<String>

    @Query("INSERT OR IGNORE INTO chronicle_deletions(kind,anchorId,deletedAt) " +
        "SELECT kind,anchorId,:now FROM chronicle_sources WHERE instr(body,:keyword) > 0")
    suspend fun redactMatchingSources(keyword: String, now: Long)

    @Query("INSERT OR IGNORE INTO chronicle_deletions(kind,anchorId,deletedAt) " +
        "SELECT s.kind,s.anchorId,:now FROM memory_derivations d JOIN chronicle_sources s ON s.id = d.sourceId " +
        "WHERE instr(d.content,:keyword) > 0 OR instr(d.quote,:keyword) > 0 OR instr(d.claimKey,:keyword) > 0")
    suspend fun redactMatchingDerivations(keyword: String, now: Long)

    @Query("DELETE FROM user_graph_attributes WHERE instr(evidence, :prefix) = 1")
    suspend fun deleteGraphAttributesForSource(prefix: String): Int

    @Query("DELETE FROM user_graph_relationships WHERE instr(evidence, :prefix) = 1")
    suspend fun deleteGraphRelationshipsForSource(prefix: String): Int

    @Query("DELETE FROM user_graph_entities WHERE memoryDerived = 1 " +
        "AND NOT EXISTS(SELECT 1 FROM user_graph_attributes a WHERE a.entityId = user_graph_entities.id) " +
        "AND NOT EXISTS(SELECT 1 FROM user_graph_relationships r WHERE r.fromEntityId = user_graph_entities.id OR r.toEntityId = user_graph_entities.id)")
    suspend fun deleteOwnedOrphanEntities(): Int

    @Query("DELETE FROM vector_entries WHERE sourceId = :id AND versionId IN " +
        "(SELECT id FROM vector_versions WHERE domain = 'memory')")
    suspend fun deleteMemoryVectorCopies(id: String): Int

    @Query("DELETE FROM memory_extraction_jobs WHERE source_type = 'GRAPH' AND source_message_id = :id")
    suspend fun deleteGraphJobsForSource(id: String): Int

    @Transaction
    suspend fun forgetMatching(keyword: String, now: Long): Int {
        require(keyword.isNotBlank())
        supersedeMatching(keyword, now)
        var after: String? = null
        var removed = 0
        while (true) {
            val ids = matchingMemoryIds(keyword, after)
            if (ids.isEmpty()) break
            ids.forEach { removed += forgetById(it, now) }
            after = ids.last()
        }
        redactMatchingSources(keyword, now)
        redactMatchingDerivations(keyword, now)
        return removed
    }
    /** 幂等插入：unique_key 冲突 → IGNORE（同内容不重复入队）。返回新行 id 或 -1（已存在）。 */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfNew(job: MemoryExtractionJobEntity): Long

    /** 查待处理作业（state=PENDING 且到退避时间），按创建序，限 N 条。 */
    @Query(
        "SELECT * FROM memory_extraction_jobs WHERE state = 'PENDING' AND next_attempt_at <= :now " +
            "ORDER BY created_at ASC LIMIT :limit"
    )
    suspend fun queryPending(now: Long, limit: Int): List<MemoryExtractionJobEntity>

    @Query("SELECT * FROM memory_extraction_jobs WHERE state = 'PENDING' AND source_type = 'USER_FACT' " +
        "AND source_message_id IS NOT NULL AND conversation_id IS NOT NULL AND next_attempt_at <= :now " +
        "ORDER BY created_at ASC LIMIT :limit")
    suspend fun queryPendingUserFacts(now: Long, limit: Int): List<MemoryExtractionJobEntity>

    /** Retain the old queue for audit, but never infer user facts from assistant-only material. */
    @Query("UPDATE memory_extraction_jobs SET state = 'SUPERSEDED', last_error = 'unsupported_assistant_source', " +
        "updated_at = :now WHERE source_type = 'AI_DEFERRED' AND state IN ('PENDING', 'RUNNING')")
    suspend fun retireAssistantSources(now: Long): Int

    /** 认领为 RUNNING（防并发重复处理）。 */
    @Query("UPDATE memory_extraction_jobs SET state = 'RUNNING', updated_at = :now WHERE id = :id AND state = 'PENDING'")
    suspend fun claimRunning(id: String, now: Long): Int

    /** 标记成功（提炼完成，含空结果——已判定无可保留）。 */
    @Query("UPDATE memory_extraction_jobs SET state = 'SUCCEEDED', updated_at = :now WHERE id = :id")
    suspend fun markSucceeded(id: String, now: Long)

    /** 失败：attempts 递增；超 MAX_ATTEMPTS → FAILED 不再自动重试；否则 PENDING + 退避。 */
    @Query(
        "UPDATE memory_extraction_jobs SET attempts = attempts + 1, last_error = :error, " +
            "state = CASE WHEN attempts + 1 >= :maxAttempts THEN 'FAILED' ELSE 'PENDING' END, " +
            "next_attempt_at = :nextAttemptAt, updated_at = :now WHERE id = :id AND state = 'PENDING'"
    )
    suspend fun markFailed(id: String, error: String, maxAttempts: Int, nextAttemptAt: Long, now: Long)

    /** 有界保留：清已完结（SUCCEEDED/FAILED/SUPERSEDED）且早于 :before 的作业。 */
    @Query("DELETE FROM memory_extraction_jobs WHERE source_type != 'GRAPH' AND state IN ('SUCCEEDED','FAILED','SUPERSEDED') AND updated_at < :before")
    suspend fun purgeCompleted(before: Long): Int

    /** 删除指定作业（级联清理，如来源消息删除时）。 */
    @Query("DELETE FROM memory_extraction_jobs WHERE source_message_id = :messageId")
    suspend fun deleteByMessageId(messageId: String): Int

    /** 调试/观测：当前各状态计数。 */
    @Query("SELECT COUNT(*) FROM memory_extraction_jobs WHERE state = :state")
    suspend fun countByState(state: String): Int

    @Query("SELECT COUNT(*) FROM memory_extraction_jobs WHERE state IN ('PENDING','RUNNING')")
    suspend fun countBacklog(): Int
}
