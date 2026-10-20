package com.zero.v2.core.data.dao

import androidx.room.*
import com.zero.v2.core.common.MemoryStatus
import com.zero.v2.core.common.MemoryType
import com.zero.v2.core.data.entity.MemoryEntity
import kotlinx.coroutines.flow.Flow

internal const val CHRONICLE_LIVE = "NOT EXISTS(SELECT 1 FROM chronicle_deletions cd WHERE " +
    "(cd.kind IN ('message','transcript') AND cd.anchorId = memories.source_message_id) OR " +
    "(cd.kind = 'conversation' AND cd.anchorId = memories.source_conversation_id) OR " +
    "(cd.kind = 'confirmation' AND memories.id = 'review:' || cd.anchorId)) AND " +
    "(memories.origin != 'MODEL_CLASSIFIED' OR EXISTS(SELECT 1 FROM memory_derivations md " +
    "JOIN chronicle_sources cs ON cs.id = md.sourceId JOIN memory_derivation_views dv ON dv.slotId = md.slotId " +
    "WHERE memories.id = 'derived:' || md.id AND md.state = 'APPLIED' AND dv.state = 'CANDIDATE' AND dv.selectedId = md.id " +
    "AND md.content = memories.content AND md.type = memories.type AND md.scope = memories.scope " +
    "AND md.importance = memories.importance AND md.createdAt = memories.created_at " +
    "AND memories.status = 'ACTIVE' AND NOT EXISTS(SELECT 1 FROM memory_deletions mt WHERE mt.id = memories.id) " +
    "AND NOT EXISTS(SELECT 1 FROM memory_conflicts mc WHERE mc.state = 'PENDING' AND (mc.leftId = memories.id OR mc.rightId = memories.id)) " +
    "AND memories.source_message_id IS CASE WHEN cs.kind = 'message' THEN cs.anchorId ELSE NULL END " +
    "AND memories.source_conversation_id IS cs.conversationId " +
    "AND (md.validFrom IS NULL OR md.validFrom <= (CAST(strftime('%s','now') AS INTEGER)*1000 + CAST(substr(strftime('%f','now'),4,3) AS INTEGER))) " +
    "AND (md.validUntil IS NULL OR md.validUntil > (CAST(strftime('%s','now') AS INTEGER)*1000 + CAST(substr(strftime('%f','now'),4,3) AS INTEGER))) " +
    "AND md.occurredAt IS memories.occurred_at AND md.validFrom IS memories.valid_from AND md.validUntil IS memories.valid_until))"

internal const val CHRONICLE_LIVE_M = "NOT EXISTS(SELECT 1 FROM chronicle_deletions cd WHERE " +
    "(cd.kind IN ('message','transcript') AND cd.anchorId = m.source_message_id) OR " +
    "(cd.kind = 'conversation' AND cd.anchorId = m.source_conversation_id) OR " +
    "(cd.kind = 'confirmation' AND m.id = 'review:' || cd.anchorId)) AND " +
    "(m.origin != 'MODEL_CLASSIFIED' OR EXISTS(SELECT 1 FROM memory_derivations md " +
    "JOIN chronicle_sources cs ON cs.id = md.sourceId JOIN memory_derivation_views dv ON dv.slotId = md.slotId " +
    "WHERE m.id = 'derived:' || md.id AND md.state = 'APPLIED' AND dv.state = 'CANDIDATE' AND dv.selectedId = md.id " +
    "AND md.content = m.content AND md.type = m.type AND md.scope = m.scope " +
    "AND md.importance = m.importance AND md.createdAt = m.created_at " +
    "AND m.status = 'ACTIVE' AND NOT EXISTS(SELECT 1 FROM memory_deletions mt WHERE mt.id = m.id) " +
    "AND NOT EXISTS(SELECT 1 FROM memory_conflicts mc WHERE mc.state = 'PENDING' AND (mc.leftId = m.id OR mc.rightId = m.id)) " +
    "AND m.source_message_id IS CASE WHEN cs.kind = 'message' THEN cs.anchorId ELSE NULL END " +
    "AND m.source_conversation_id IS cs.conversationId " +
    "AND (md.validFrom IS NULL OR md.validFrom <= (CAST(strftime('%s','now') AS INTEGER)*1000 + CAST(substr(strftime('%f','now'),4,3) AS INTEGER))) " +
    "AND (md.validUntil IS NULL OR md.validUntil > (CAST(strftime('%s','now') AS INTEGER)*1000 + CAST(substr(strftime('%f','now'),4,3) AS INTEGER))) " +
    "AND md.occurredAt IS m.occurred_at AND md.validFrom IS m.valid_from AND md.validUntil IS m.valid_until))"

@Dao
interface MemoryDao {
    @Query("SELECT * FROM memory_sources WHERE summaryId IN (:ids) ORDER BY summaryId, sourceId LIMIT 1025")
    suspend fun chronicleDependencies(ids: List<String>): List<com.zero.v2.core.data.entity.MemorySourceEntity>

    /** No access accounting, certification, schema migration, or history edits. Reject over-budget graphs. */
    @Transaction
    suspend fun readChronicleSnapshot(ids: List<String>): com.zero.v2.core.data.entity.MemoryChronicleSnapshot {
        require(ids.size in 1..64 && ids.distinct().size == ids.size && ids.none { it.isBlank() })
        val entries = ids.mapNotNull { readEvidenceSnapshot(it) }
        val dependencies = chronicleDependencies(entries.map { it.memory.id })
        require(dependencies.size <= 1024) { "chronicle_dependency_limit" }
        return com.zero.v2.core.data.entity.MemoryChronicleSnapshot(entries, dependencies)
    }

    @Query("SELECT * FROM messages WHERE id = :id")
    suspend fun evidenceSourceMessage(id: String): com.zero.v2.core.data.entity.MessageEntity?

    @Query("SELECT EXISTS(SELECT 1 FROM memory_conflicts WHERE state = 'PENDING' AND (leftId = :id OR rightId = :id))")
    suspend fun hasPendingEvidenceConflict(id: String): Boolean

    @Query("SELECT EXISTS(SELECT 1 FROM memories WHERE supersedes_id = :id AND status = 'ACTIVE')")
    suspend fun hasActiveEvidenceReplacement(id: String): Boolean

    @Query("SELECT * FROM memory_reviews WHERE resultMemoryId = :id")
    suspend fun evidenceConfirmations(id: String): List<com.zero.v2.core.data.entity.MemoryReviewEntity>

    @Query("SELECT EXISTS(SELECT 1 FROM memory_sources WHERE summaryId = :id AND sourceId = :parent)")
    suspend fun hasEvidenceParent(id: String, parent: String): Boolean

    @Transaction
    suspend fun readEvidenceSnapshot(id: String): com.zero.v2.core.data.entity.MemoryEvidenceSnapshot? {
        val memory = getById(id)?.takeUnless { wasDeleted(id) } ?: return null
        val blocked = recallBlocked(id)
        val source = memory.sourceMessageId?.takeUnless { blocked }?.let { evidenceSourceMessage(it) }
        val review = evidenceConfirmations(id).singleOrNull()?.takeUnless { blocked || confirmationRedacted(it.id) }
        val parent = review?.memoryId?.let { getById(it)?.takeUnless { wasDeleted(it.id) } }
        return com.zero.v2.core.data.entity.MemoryEvidenceSnapshot(memory, source,
            hasPendingEvidenceConflict(id), hasActiveEvidenceReplacement(id), review,
            parent?.let(com.zero.v2.core.data.entity.MemoryConflictEntity::version),
            parent != null && hasEvidenceParent(id, parent.id))
    }

    @Query("SELECT * FROM memories WHERE status = 'ACTIVE' AND ((origin = 'USER_QUOTE' AND source_message_id IS NOT NULL) OR " +
        "(origin = 'USER_CONFIRMED' AND EXISTS(SELECT 1 FROM memory_reviews r WHERE r.resultMemoryId = memories.id AND r.state = 'ACCEPTED'))) " +
        "AND (valid_from IS NULL OR valid_from <= :now) AND (valid_until IS NULL OR valid_until > :now) " +
        "AND (type = 'DIRECTIVE' OR importance >= 0.8 OR supersedes_id IS NOT NULL) " +
        "AND " + CHRONICLE_LIVE + " " +
        "ORDER BY CASE WHEN type = 'DIRECTIVE' THEN 0 ELSE 1 END, importance DESC, created_at DESC, id LIMIT 32")
    suspend fun importantMemories(now: Long): List<MemoryEntity>

    @Query("SELECT * FROM memories WHERE id IN (:ids) AND status = 'ACTIVE' AND (origin = 'USER_QUOTE' OR " +
        "(origin = 'USER_CONFIRMED' AND EXISTS(SELECT 1 FROM memory_reviews r WHERE r.resultMemoryId = memories.id AND r.state = 'ACCEPTED'))) " +
        "AND (valid_from IS NULL OR valid_from <= :now) AND (valid_until IS NULL OR valid_until > :now) AND " + CHRONICLE_LIVE)
    suspend fun currentMemories(ids: List<String>, now: Long): List<MemoryEntity>

    /** Raw bounded candidates: the source reader, not a cached view, qualifies each before use. */
    @Query("SELECT * FROM memories WHERE origin = 'MODEL_CLASSIFIED' AND status = 'ACTIVE' AND scope = :scope " +
        "AND (valid_from IS NULL OR valid_from <= :now) AND (valid_until IS NULL OR valid_until > :now) " +
        "AND (type IN ('DIRECTIVE','RELATIONSHIP') OR " +
        "(type = 'TASK' AND (instr(content, :activeTaskPrefix) = 1 OR instr(content, :waitingTaskPrefix) = 1)) OR " +
        "(type IN ('FACT','PREFERENCE') AND (importance >= 0.8 OR access_count >= 2))) " +
        "ORDER BY CASE type WHEN 'DIRECTIVE' THEN 0 WHEN 'RELATIONSHIP' THEN 1 WHEN 'TASK' THEN 2 ELSE 3 END, " +
        "access_count DESC, importance DESC, id LIMIT 32")
    suspend fun managedContextCandidates(scope: String, now: Long, activeTaskPrefix: String, waitingTaskPrefix: String): List<MemoryEntity>
    @Query("SELECT * FROM memory_conflicts WHERE state = 'PENDING' ORDER BY createdAt, id")
    fun observeConflicts(): Flow<List<com.zero.v2.core.data.entity.MemoryConflictEntity>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertConflict(value: com.zero.v2.core.data.entity.MemoryConflictEntity)

    @Query("SELECT * FROM memory_conflicts WHERE id = :id")
    suspend fun conflict(id: String): com.zero.v2.core.data.entity.MemoryConflictEntity?

    @Query("UPDATE memory_conflicts SET state = :state, resolvedAt = :now WHERE id = :id AND state = 'PENDING'")
    suspend fun markConflict(id: String, state: String, now: Long)

    @Transaction
    suspend fun resolveConflict(id: String, choice: String, now: Long): Boolean {
        require(choice in setOf("LEFT", "RIGHT", "BOTH"))
        val c = conflict(id)?.takeIf { it.state == "PENDING" } ?: return false
        val a = getById(c.leftId); val b = getById(c.rightId)
        if (a == null || b == null || a.status != MemoryStatus.ACTIVE || b.status != MemoryStatus.ACTIVE ||
            com.zero.v2.core.data.entity.MemoryConflictEntity.version(a) != c.leftVersion ||
            com.zero.v2.core.data.entity.MemoryConflictEntity.version(b) != c.rightVersion) {
            markConflict(id, "STALE", now)
            return false
        }
        if (choice != "BOTH") {
            val kept = if (choice == "LEFT") a else b
            val archived = if (choice == "LEFT") b else a
            insert(kept.copy(supersedesId = archived.id, revision = kept.revision + 1))
            updateStatus(archived.id, MemoryStatus.ARCHIVED)
            archiveDerived(archived.id)
        }
        markConflict(id, choice, now)
        return true
    }

    @Query("WITH RECURSIVE affected(id) AS (SELECT summaryId FROM memory_sources WHERE sourceId = :id UNION " +
        "SELECT s.summaryId FROM memory_sources s JOIN affected a ON s.sourceId = a.id) " +
        "UPDATE memories SET status = 'ARCHIVED' WHERE id IN (SELECT id FROM affected)")
    suspend fun archiveDerived(id: String)
    /** Optimistic commit: a delayed decision must not act on edited or deleted rows. */
    @Transaction
    suspend fun archiveDuplicate(kept: MemoryEntity, removed: MemoryEntity): Boolean {
        fun matches(current: MemoryEntity?, expected: MemoryEntity): Boolean =
            current != null && current.status == MemoryStatus.ACTIVE &&
                current.copy(embedding = null, accessCount = 0, reinforcementCount = 0, lastAccessedAt = 0) ==
                expected.copy(embedding = null, accessCount = 0, reinforcementCount = 0, lastAccessedAt = 0)
        if (!matches(getById(kept.id), kept) || !matches(getById(removed.id), removed)) return false
        updateStatus(removed.id, MemoryStatus.ARCHIVED)
        return true
    }
    /** Recheck delayed retrieval against current sources, then record access in the same transaction. */
    @Transaction
    suspend fun recordCurrentRecall(snapshots: List<MemoryEntity>, limit: Int): List<MemoryEntity> {
        val current = snapshots.mapNotNull { snapshot ->
            getById(snapshot.id)?.takeIf {
                it.status == MemoryStatus.ACTIVE && it.content == snapshot.content && !recallBlocked(it.id)
            }
        }.take(limit)
        current.forEach { recordAccess(it.id) }
        return current
    }

    @Query("SELECT * FROM memories WHERE status = 'ACTIVE' AND (instr(content, :query) > 0 OR instr(COALESCE(keywords, ''), :query) > 0) ORDER BY importance DESC LIMIT :limit")
    suspend fun searchActiveByKeyword(query: String, limit: Int = 100): List<MemoryEntity>

    @RawQuery
    suspend fun keywordGroupRows(query: androidx.sqlite.db.SupportSQLiteQuery): List<com.zero.v2.core.data.entity.MemoryLexicalGroupRow>

    @Query("SELECT EXISTS(SELECT id FROM memories LIMIT 1 OFFSET 2000)")
    suspend fun hasManyLexicalRows(): Boolean

    /** Legacy reads for small tables; one grouped snapshot for large tables. No cached facts. */
    suspend fun searchActiveKeywordGroups(terms: List<String>, limit: Int = 100): List<List<MemoryEntity>> {
        require(terms.size <= 8 && limit in 1..100)
        if (terms.isEmpty()) return emptyList()
        if (!hasManyLexicalRows()) {
            return terms.map { searchActiveByKeyword(it, limit) }
        }
        val rows = keywordGroupRows(LexicalGroupQuery.memory(terms, limit))
        val entities = mutableMapOf<String, MemoryEntity>()
        val groups = List(terms.size) { mutableListOf<MemoryEntity>() }
        rows.forEach { row ->
            if (row.termIndex == -1) entities[row.matchId] = checkNotNull(row.memory)
            else groups[row.termIndex].add(checkNotNull(entities[row.matchId]))
        }
        return groups
    }
    /** Reject summaries computed from deleted/changed input; insert and archive atomically. */
    @Transaction
    suspend fun commitCompression(sources: List<MemoryEntity>, summary: MemoryEntity): Boolean {
        if (sources.isEmpty() || sources.map { it.id }.distinct().size != sources.size ||
            sources.any { it.id == summary.id || it.type == MemoryType.DIRECTIVE } ||
            getById(summary.id) != null || wasDeleted(summary.id)) return false
        if (sources.any { source ->
            val current = getById(source.id)
            current == null || current.status != MemoryStatus.ACTIVE || current.content != source.content ||
                current.revision != source.revision || current.type != source.type ||
                current.origin != source.origin || wasDeleted(source.id) || recallBlocked(source.id)
        }) return false
        insert(summary)
        insertSources(sources.map { com.zero.v2.core.data.entity.MemorySourceEntity(summary.id, it.id) })
        sources.forEach { updateStatus(it.id, MemoryStatus.ARCHIVED) }
        return true
    }

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertSources(sources: List<com.zero.v2.core.data.entity.MemorySourceEntity>)

    @Query("SELECT sourceId FROM memory_sources WHERE summaryId = :id ORDER BY sourceId")
    suspend fun summarySources(id: String): List<String>

    @Query("SELECT * FROM memory_sources ORDER BY summaryId, sourceId")
    suspend fun allSources(): List<com.zero.v2.core.data.entity.MemorySourceEntity>
    @Query("SELECT * FROM memories ORDER BY importance DESC")
    fun observeAll(): Flow<List<MemoryEntity>>

    @Query("SELECT * FROM memories WHERE level = :level ORDER BY importance DESC")
    suspend fun getByLevel(level: Int): List<MemoryEntity>

    @Query("SELECT * FROM memories WHERE tier = :tier ORDER BY importance DESC")
    suspend fun getByTier(tier: Int): List<MemoryEntity>

    @Query("SELECT * FROM memories WHERE keywords LIKE '%' || :query || '%' OR content LIKE '%' || :query || '%' ORDER BY importance DESC")
    suspend fun searchByKeyword(query: String): List<MemoryEntity>

    @Query("SELECT * FROM memories WHERE source_conversation_id = :conversationId ORDER BY created_at DESC")
    suspend fun getByConversation(conversationId: String): List<MemoryEntity>

    // ═══ v4.1: 类型/状态/过期查询（Phase 4 演化地基）═══

    @Query("SELECT * FROM memories WHERE type = :type AND status = 'ACTIVE' ORDER BY importance DESC")
    suspend fun getByType(type: MemoryType): List<MemoryEntity>

    @Query("SELECT * FROM memories WHERE status = :status ORDER BY created_at DESC")
    suspend fun getByStatus(status: MemoryStatus): List<MemoryEntity>

    /** 归档候选：仍 ACTIVE 且 :before 之前未访问（§3.4 默认 90 天） */
    @Query("SELECT * FROM memories WHERE status = 'ACTIVE' AND type != 'DIRECTIVE' AND origin != 'MODEL_CLASSIFIED' AND last_accessed_at < :before")
    suspend fun getExpired(before: Long): List<MemoryEntity>

    @Query("SELECT * FROM memories WHERE id = :id")
    suspend fun getById(id: String): MemoryEntity?

    /**
     * R2：有界候选——携带[当前模型]向量的 ACTIVE 记忆，按重要性/时间预筛 LIMIT [limit]
     * （替代旧全表扫描 getActiveWithEmbedding —— L-7 债收口）。
     *
     * embed_model 过滤排除换档前的旧向量（维度/模型不匹配 → 调用方走词法降级，永不哑火）。
     * 余弦在 Kotlin 层计算（D31 同款）；excludeId 用于 findSimilar 自排除。
     */
    @Query("SELECT * FROM memories WHERE status = 'ACTIVE' AND embedding IS NOT NULL " +
            "AND embed_model = :model AND id != :excludeId " +
            "ORDER BY importance DESC, created_at DESC LIMIT :limit")
    suspend fun getActiveWithEmbeddingBounded(model: String, excludeId: String, limit: Int): List<MemoryEntity>

    @Query("SELECT * FROM memories WHERE status = 'ACTIVE' AND embedding IS NOT NULL " +
        "AND embed_model = :model AND id > :after ORDER BY id LIMIT :limit")
    suspend fun embeddingPage(model: String, after: String, limit: Int): List<MemoryEntity>

    // ═══ v4.6-pre: Embedding 回填（D31 检索地基）═══

    /**
     * R2：换档重嵌游标——embedding 为空 OR embed_model != [model]（P2 债收口：补 model-mismatch
     * 触发的 re-embed 路径）。backfill 消费 → 回写新向量 + 当前 embed_model → 自稳（下次不再命中）。
     * 索引失效/重建可恢复：换档后旧向量被有界查询排除（走词法），backfill 重嵌后向量路恢复。
     */
    @Query("SELECT * FROM memories WHERE embedding IS NULL OR embed_model != :model " +
            "ORDER BY created_at DESC LIMIT :limit")
    suspend fun getForReembedding(model: String, limit: Int): List<MemoryEntity>

    @Query("SELECT COUNT(*) FROM memories WHERE embedding IS NULL OR embed_model != :model")
    suspend fun countEmbeddingBacklog(model: String): Int

    /** R2：回写向量 + embed_model（换档防护落列；与 KbDao.updateEmbedding 同律）。 */
    @Query("UPDATE memories SET embedding = :embedding, embed_model = :model WHERE id = :id")
    suspend fun updateEmbedding(id: String, embedding: ByteArray, model: String)

    /** v4.5: 冲突解决 — 状态流转（KEEP_NEWER 归档旧版本） */
    @Query("UPDATE memories SET status = :status WHERE id = :id")
    suspend fun updateStatus(id: String, status: MemoryStatus)

    @Query("SELECT * FROM memories WHERE status = 'ACTIVE' AND instr(content, :keyword) > 0 ORDER BY id LIMIT 257")
    suspend fun withdrawalMatches(keyword: String): List<MemoryEntity>

    /** v4.5: 冲突解决 — 双方降权（下限 0） */
    @Query("UPDATE memories SET confidence = MAX(0.0, confidence - :delta) WHERE id = :id")
    suspend fun downgradeConfidence(id: String, delta: Float)

    /** v4.7: 压缩取数 — 最老的未归档低层记忆（HistoryCompressor 批次输入） */
    @Query("SELECT * FROM memories WHERE status = 'ACTIVE' AND type != 'DIRECTIVE' AND origin != 'MODEL_CLASSIFIED' AND level < :maxLevel AND " +
        CHRONICLE_LIVE + " ORDER BY created_at ASC LIMIT :limit")
    suspend fun getOldestForCompression(maxLevel: Int, limit: Int): List<MemoryEntity>

    /** v4.9: 图谱提取输入 — 指定时间后的 ACTIVE 记忆（升序批次） */
    @Query("SELECT * FROM memories WHERE status = 'ACTIVE' AND created_at >= :since ORDER BY created_at ASC LIMIT :limit")
    suspend fun getActiveSince(since: Long, limit: Int): List<MemoryEntity>

    /** Filter provenance before LIMIT so model-authored notes cannot crowd out user evidence. */
    @Query("SELECT * FROM memories WHERE status = 'ACTIVE' AND type = 'FACT' " +
        "AND id LIKE 'user-fact:%' AND source_conversation_id IS NOT NULL " +
        "AND trim(source_conversation_id) != '' AND created_at >= :since AND " + CHRONICLE_LIVE +
        " ORDER BY created_at ASC LIMIT :limit")
    suspend fun getGroundedFactsSince(since: Long, limit: Int): List<MemoryEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertStored(memory: MemoryEntity)

    @Query("SELECT EXISTS(SELECT 1 FROM memory_deletions WHERE id = :id)")
    suspend fun wasDeleted(id: String): Boolean

    @Query("SELECT EXISTS(SELECT 1 FROM memories WHERE id = :id AND NOT (" + CHRONICLE_LIVE + "))")
    suspend fun recallBlocked(id: String): Boolean

    @Query("SELECT EXISTS(SELECT 1 FROM chronicle_deletions WHERE kind IN ('message','transcript') AND anchorId = :id)")
    suspend fun sourceRedacted(id: String): Boolean

    @Query("SELECT EXISTS(SELECT 1 FROM chronicle_deletions WHERE kind = 'conversation' AND anchorId = :id)")
    suspend fun conversationRedacted(id: String): Boolean

    @Query("SELECT EXISTS(SELECT 1 FROM chronicle_deletions WHERE kind = 'confirmation' AND anchorId = :id)")
    suspend fun confirmationRedacted(id: String): Boolean

    @Transaction
    suspend fun insert(memory: MemoryEntity) {
        if (!wasDeleted(memory.id) && (memory.sourceMessageId?.let { !sourceRedacted(it) } ?: true) &&
            (memory.sourceConversationId?.let { !conversationRedacted(it) } ?: true)) insertStored(memory)
    }

    @Transaction
    suspend fun insertAll(memories: List<MemoryEntity>) {
        memories.forEach { insert(it) }
    }

    /** 检索命中：访问计数 + 强化计数同增（§3.7 reinforcementBonus 数据源） */
    @Query("UPDATE memories SET access_count = access_count + 1, reinforcement_count = reinforcement_count + 1, last_accessed_at = :now WHERE id = :id")
    suspend fun recordAccess(id: String, now: Long = System.currentTimeMillis())

    @Query("DELETE FROM memories WHERE id = :id")
    suspend fun delete(id: String)

    @Query("DELETE FROM memories WHERE level = :level")
    suspend fun deleteByLevel(level: Int)

    @Query("SELECT * FROM memories ORDER BY last_accessed_at ASC LIMIT :limit")
    suspend fun getLeastRecentlyAccessed(limit: Int): List<MemoryEntity>

    @Query("SELECT * FROM memories WHERE supersedes_id=:id AND scope=:scope ORDER BY created_at DESC,id LIMIT 17")
    suspend fun replacementsOf(id: String, scope: String): List<MemoryEntity>

    @Query("SELECT COUNT(*) FROM memories")
    suspend fun count(): Int

    /** Reversible quarantine of the two reported legacy template facts, only without user provenance. */
    @Query("UPDATE memories SET status = 'ARCHIVED' WHERE status = 'ACTIVE' AND type = 'FACT' " +
        "AND confidence <= 0.5 AND source_conversation_id IS NULL AND trim(content) IN " +
        "('用户可能随时发消息打断助手', '用户可能随时发消息打断助手。', " +
        "'用户偏好提醒类消息通过微信送达', '用户偏好提醒类消息通过微信送达。')")
    suspend fun quarantineReportedTemplateFacts(): Int

    /** Phase 10 预留：FTS5 全文检索 */
    @RawQuery
    suspend fun rawQuery(query: androidx.sqlite.db.SimpleSQLiteQuery): List<MemoryEntity>
}
