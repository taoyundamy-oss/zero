package com.zero.v2.core.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.zero.v2.core.data.entity.GraphMemoryEvidence
import com.zero.v2.core.data.entity.MemoryEntity
import com.zero.v2.core.data.entity.MemoryExtractionJobEntity
import com.zero.v2.core.data.entity.UserGraphAttribute
import com.zero.v2.core.data.entity.UserGraphEntity
import com.zero.v2.core.data.entity.UserGraphRelationship

/**
 * 用户图谱 DAO — Step 4.10。
 *
 * 属性版本化语义: 写新值 = closeCurrentAttribute（关闭旧区间）+ insertAttribute（新区间）。
 */
@Dao
interface UserGraphDao {
    @Query("SELECT m.* FROM memories m WHERE m.status = 'ACTIVE' AND m.type = 'FACT' " +
        "AND " + CHRONICLE_LIVE_M + " " +
        "AND m.id LIKE 'user-fact:%' AND trim(COALESCE(m.source_conversation_id,'')) != '' " +
        "AND NOT EXISTS(SELECT 1 FROM memory_extraction_jobs j WHERE j.source_type = 'GRAPH' " +
        "AND j.source_message_id = m.id AND j.source_digest = m.content AND j.conversation_id = m.source_conversation_id " +
        "AND j.created_at = m.created_at) ORDER BY m.created_at,m.id LIMIT :limit")
    suspend fun unqueuedGraphSources(limit: Int): List<MemoryEntity>

    @Query("DELETE FROM memory_extraction_jobs WHERE source_type = 'GRAPH' AND NOT EXISTS(" +
        "SELECT 1 FROM memories m WHERE m.id = memory_extraction_jobs.source_message_id " +
        "AND m.content = memory_extraction_jobs.source_digest " +
        "AND m.source_conversation_id = memory_extraction_jobs.conversation_id AND m.created_at = memory_extraction_jobs.created_at " +
        "AND m.status = 'ACTIVE' AND m.type = 'FACT' AND " + CHRONICLE_LIVE_M + ")")
    suspend fun discardStaleGraphJobs(): Int

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertGraphJob(job: MemoryExtractionJobEntity): Long

    @Transaction
    suspend fun enqueueGraphSources(limit: Int = 50) {
        require(limit in 1..50)
        discardStaleGraphJobs()
        unqueuedGraphSources(limit).forEach { source ->
            val key = "graph:" + GraphMemoryEvidence.reference(source)
            insertGraphJob(MemoryExtractionJobEntity(key, "GRAPH", source.sourceConversationId, source.id,
                source.content, key, createdAt = source.createdAt))
        }
    }

    @Query("SELECT * FROM memory_extraction_jobs WHERE source_type = 'GRAPH' AND state = 'PENDING' " +
        "AND next_attempt_at <= :now ORDER BY created_at,id LIMIT :limit")
    suspend fun pendingGraphJobs(now: Long, limit: Int = 50): List<MemoryExtractionJobEntity>

    @Query("SELECT * FROM memory_extraction_jobs WHERE id = :id")
    suspend fun graphJob(id: String): MemoryExtractionJobEntity?

    @Query("UPDATE memory_extraction_jobs SET state = 'SUCCEEDED', updated_at = :now WHERE id = :id")
    suspend fun completeGraphJob(id: String, now: Long)

    /** Graph writes and durable completion share one transaction; external model work occurs before this. */
    @Transaction
    suspend fun commitGraphBatch(
        jobs: List<MemoryExtractionJobEntity>, sources: List<MemoryEntity>,
        entities: List<UserGraphEntity>, attributes: List<UserGraphAttribute>,
        relationships: List<UserGraphRelationship>, now: Long,
    ): Boolean {
        require(jobs.size in 1..50 && jobs.size == sources.size)
        if (jobs.zip(sources).any { (job, source) ->
            val current = getAttributeSource(source.id)
            graphJob(job.id) != job || job.state != "PENDING" || job.sourceType != "GRAPH" ||
                job.sourceMessageId != source.id || job.sourceDigest != source.content ||
                job.conversationId != source.sourceConversationId || job.createdAt != source.createdAt ||
                !GraphMemoryEvidence.eligible(source) ||
                current == null || !GraphMemoryEvidence.eligible(current) ||
                GraphMemoryEvidence.reference(current) != GraphMemoryEvidence.reference(source)
        }) return false
        val refs = sources.associateBy { GraphMemoryEvidence.reference(it) }
        require(attributes.all { it.evidence in refs && it.value == refs[it.evidence]?.content })
        require(relationships.all { it.evidence in refs })
        entities.forEach { entity ->
            val existing = getEntity(entity.id)
            // Preserve legacy/manual entity names and ownership; the model cannot relabel them.
            if (existing == null) upsertEntity(entity.copy(memoryDerived = true))
        }
        attributes.forEach { check(replaceGroundedAttribute(it, refs.getValue(it.evidence!!))) }
        relationships.forEach { check(replaceGroundedRelationship(it, refs.getValue(it.evidence!!))) }
        jobs.forEach { completeGraphJob(it.id, now) }
        return true
    }

    // ═══ 实体 ═══

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertEntity(entity: UserGraphEntity)

    @Query("SELECT * FROM user_graph_entities WHERE id = :id")
    suspend fun getEntity(id: String): UserGraphEntity?

    @Query("SELECT * FROM user_graph_entities ORDER BY createdAt DESC")
    suspend fun getAllEntities(): List<UserGraphEntity>

    // ═══ 属性（时间区间版本化）═══

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAttribute(attribute: UserGraphAttribute)

    /** 关闭旧区间和插入新值必须同时成功；插入失败时保留旧有效值。 */
    @Transaction
    suspend fun replaceCurrentAttribute(attribute: UserGraphAttribute) {
        require(attribute.effectiveTo == null)
        closeCurrentAttribute(attribute.entityId, attribute.key, attribute.effectiveFrom)
        insertAttribute(attribute)
    }

    @Query("SELECT * FROM memories WHERE id = :id AND " + CHRONICLE_LIVE)
    suspend fun getAttributeSource(id: String): MemoryEntity?

    suspend fun hasNewerSource(evidence: String?, incoming: MemoryEntity): Boolean {
        val previous = evidence?.let { GraphMemoryEvidence.memoryId(it) }?.let { getAttributeSource(it) } ?: return false
        if (!GraphMemoryEvidence.eligible(previous) || GraphMemoryEvidence.reference(previous) != evidence) return false
        return previous.createdAt > incoming.createdAt ||
            (previous.createdAt == incoming.createdAt && previous.id > incoming.id)
    }

    /** Recheck after the model request, atomically with closing the previous version. */
    @Transaction
    suspend fun replaceGroundedAttribute(attribute: UserGraphAttribute, snapshot: MemoryEntity): Boolean {
        val current = getAttributeSource(snapshot.id) ?: return false
        val reference = GraphMemoryEvidence.reference(snapshot)
        if (!GraphMemoryEvidence.eligible(current) || !GraphMemoryEvidence.eligible(snapshot) ||
            GraphMemoryEvidence.reference(current) != reference || attribute.evidence != reference ||
            attribute.value != snapshot.content) return false
        val previous = getStoredCurrentAttributes(attribute.entityId).firstOrNull { it.key == attribute.key }
        if (hasNewerSource(previous?.evidence, snapshot)) return true
        replaceCurrentAttribute(attribute)
        return true
    }

    /** 关闭当前有效区间（写新值前调用 → 旧版归档、新版生效） */
    @Query(
        """
        UPDATE user_graph_attributes SET effectiveTo = :effectiveTo
        WHERE entityId = :entityId AND `key` = :key AND effectiveTo IS NULL
        """
    )
    suspend fun closeCurrentAttribute(entityId: String, key: String, effectiveTo: Long)

    @Query("SELECT * FROM user_graph_attributes WHERE entityId = :entityId AND effectiveTo IS NULL")
    suspend fun getStoredCurrentAttributes(entityId: String): List<UserGraphAttribute>

    /** Legacy evidence remains untouched; versioned references must still match a live source. */
    @Transaction
    suspend fun getCurrentAttributes(entityId: String): List<UserGraphAttribute> =
        getStoredCurrentAttributes(entityId).filter { attribute ->
            val evidence = attribute.evidence
            if (evidence == null || !evidence.startsWith("memory:")) true else {
                val memory = GraphMemoryEvidence.memoryId(evidence)?.let { getAttributeSource(it) }
                memory != null && GraphMemoryEvidence.eligible(memory) &&
                    GraphMemoryEvidence.reference(memory) == evidence
            }
        }

    @Query("SELECT * FROM user_graph_attributes WHERE entityId = :entityId ORDER BY effectiveFrom DESC")
    suspend fun getAttributes(entityId: String): List<UserGraphAttribute>

    @Query("SELECT COUNT(*) FROM user_graph_attributes WHERE effectiveTo IS NULL")
    suspend fun countCurrentAttributes(): Long

    /** v4.11 (D25): 每月未确认衰减 — 当前有效且 effectiveFrom 早于 :before 的属性 -delta（下限 0）。返回受影响行数 */
    @Query(
        """
        UPDATE user_graph_attributes SET confidence = MAX(0.0, confidence - :delta)
        WHERE effectiveTo IS NULL AND effectiveFrom < :before
        """
    )
    suspend fun decayCurrentConfidence(before: Long, delta: Float): Int

    // ═══ 关系 ═══

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRelationship(relationship: UserGraphRelationship)

    @Query("UPDATE user_graph_relationships SET effectiveTo = :until " +
        "WHERE fromEntityId = :fromId AND toEntityId = :toId AND type = :type AND effectiveTo IS NULL")
    suspend fun closeCurrentRelationship(fromId: String, toId: String, type: String, until: Long)

    @Query("SELECT * FROM user_graph_relationships WHERE fromEntityId = :fromId AND toEntityId = :toId " +
        "AND type = :type ORDER BY effectiveFrom DESC LIMIT 1")
    suspend fun latestRelationship(fromId: String, toId: String, type: String): UserGraphRelationship?

    /** A stale model response must neither create an edge nor retire the current one. */
    @Transaction
    suspend fun replaceGroundedRelationship(edge: UserGraphRelationship, snapshot: MemoryEntity): Boolean {
        require(edge.effectiveTo == null || edge.effectiveTo == edge.effectiveFrom)
        val current = getAttributeSource(snapshot.id) ?: return false
        val reference = GraphMemoryEvidence.reference(snapshot)
        if (!GraphMemoryEvidence.eligible(snapshot) || !GraphMemoryEvidence.eligible(current) ||
            GraphMemoryEvidence.reference(current) != reference || edge.evidence != reference) return false
        val previous = latestRelationship(edge.fromEntityId, edge.toEntityId, edge.type)
        if (hasNewerSource(previous?.evidence, snapshot)) return true
        closeCurrentRelationship(edge.fromEntityId, edge.toEntityId, edge.type, edge.effectiveFrom)
        insertRelationship(edge)
        return true
    }

    @Query("SELECT * FROM user_graph_relationships WHERE fromEntityId = :fromId AND effectiveTo IS NULL")
    suspend fun getStoredCurrentRelationships(fromId: String): List<UserGraphRelationship>

    @Transaction
    suspend fun getCurrentRelationships(fromId: String): List<UserGraphRelationship> =
        getStoredCurrentRelationships(fromId).filter { edge ->
            val evidence = edge.evidence
            if (evidence == null || !evidence.startsWith("memory:")) true else {
                val source = GraphMemoryEvidence.memoryId(evidence)?.let { getAttributeSource(it) }
                source != null && GraphMemoryEvidence.eligible(source) &&
                    GraphMemoryEvidence.reference(source) == evidence
            }
        }
}
