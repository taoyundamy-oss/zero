package com.zero.v2.core.data.dao

import androidx.room.*
import com.zero.v2.core.data.entity.ChronicleDeletionEntity
import com.zero.v2.core.data.entity.ChronicleSourceEntity
import com.zero.v2.core.data.entity.MemorySearchQuery
import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.sqlite.db.SupportSQLiteQuery

/** Shared by the two explicit new-source writers. Restore never infers a source from old messages. */
interface ChronicleSourceWriter {
    @Query("SELECT EXISTS(SELECT 1 FROM chronicle_sources WHERE kind = :kind AND anchorId = :anchor)")
    suspend fun hasAnchor(kind: String, anchor: String): Boolean

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertChronicle(value: ChronicleSourceEntity): Long

    @Query("SELECT * FROM chronicle_sources WHERE id = :id")
    suspend fun chronicle(id: String): ChronicleSourceEntity?

    @Query("SELECT EXISTS(SELECT 1 FROM chronicle_deletions WHERE ((kind = :kind OR " +
        "(kind = 'transcript' AND :kind = 'message')) AND anchorId = :anchor) " +
        "OR (kind = 'conversation' AND anchorId = :conversation)) OR " +
        "EXISTS(SELECT 1 FROM memory_deletions WHERE id = :parent)")
    suspend fun chronicleBlocked(kind: String, anchor: String, conversation: String?, parent: String?): Boolean

    @Transaction
    suspend fun retainSource(value: ChronicleSourceEntity): Boolean {
        value.validate()
        if (chronicleBlocked(value.kind, value.anchorId, value.conversationId, value.parentMemoryId)) return false
        val old = chronicle(value.id)
        require(old == null || old.copy(capturedAt = value.capturedAt) == value) { "chronicle_identity_collision" }
        return insertChronicle(value) != -1L
    }
}

@Dao
interface ChronicleSourceDao : ChronicleSourceWriter {
    @RawQuery(observedEntities = [com.zero.v2.core.data.entity.MessageEntity::class])
    suspend fun historicalMessages(query: SupportSQLiteQuery): List<com.zero.v2.core.data.entity.MessageEntity>

    @Query("SELECT * FROM messages WHERE id=:id")
    suspend fun historicalMessage(id: String): com.zero.v2.core.data.entity.MessageEntity?

    @RawQuery(observedEntities = [ChronicleSourceEntity::class])
    suspend fun searchBound(query: SupportSQLiteQuery): List<ChronicleSourceEntity>

    /** AND across literal terms, with deletions filtered before LIMIT; no bounded recent-window scan. */
    suspend fun searchTerms(query: String, limit: Int): List<ChronicleSourceEntity> {
        val plan = MemorySearchQuery.sources(query, limit)
        return searchBound(SimpleSQLiteQuery(plan.sql, plan.args.toTypedArray()))
    }
    @Query("SELECT * FROM chronicle_sources WHERE instr(body,:query) > 0 ORDER BY capturedAt DESC,id LIMIT :limit")
    suspend fun recent(query: String, limit: Int): List<ChronicleSourceEntity>

    @Query("SELECT EXISTS(SELECT 1 FROM chronicle_deletions WHERE (kind = 'transcript' AND anchorId = :id) " +
        "OR (kind = 'conversation' AND anchorId = :conversation))")
    suspend fun transcriptDeleted(id: String, conversation: String): Boolean

    @Query("SELECT * FROM chronicle_sources ORDER BY recordedAt, id")
    suspend fun all(): List<ChronicleSourceEntity>

    @Query("SELECT * FROM chronicle_deletions ORDER BY kind, anchorId")
    suspend fun deletions(): List<ChronicleDeletionEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertDeletion(value: ChronicleDeletionEntity)

    @Transaction
    suspend fun redact(value: ChronicleDeletionEntity) {
        value.validate()
        insertDeletion(value)
        purge(value.kind, value.anchorId)
    }

    @Query("DELETE FROM chronicle_sources WHERE ((kind = :kind OR (:kind = 'transcript' AND kind = 'message')) " +
        "AND anchorId = :anchor) " +
        "OR (:kind = 'conversation' AND conversationId = :anchor)")
    suspend fun purge(kind: String, anchor: String)
}
