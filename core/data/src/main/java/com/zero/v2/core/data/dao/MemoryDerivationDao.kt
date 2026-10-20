package com.zero.v2.core.data.dao

import androidx.room.*
import com.zero.v2.core.data.entity.MemoryDerivationEntity
import com.zero.v2.core.data.entity.MemoryDerivationViewEntity
import com.zero.v2.core.data.entity.MemorySlotKey
import com.zero.v2.core.data.entity.MemorySearchQuery
import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.sqlite.db.SupportSQLiteQuery

@Dao
interface MemoryDerivationDao {
    @RawQuery(observedEntities = [MemoryDerivationEntity::class])
    suspend fun matchingSlotQuery(query: SupportSQLiteQuery): List<MemorySlotKey>

    suspend fun matchingSlots(scope: String, query: String): List<MemorySlotKey> {
        val plan = MemorySearchQuery.slots(scope, query) ?: return emptyList()
        return matchingSlotQuery(SimpleSQLiteQuery(plan.sql, plan.args.toTypedArray()))
    }
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(value: MemoryDerivationEntity): Long

    @Query("SELECT * FROM memory_derivations WHERE id = :id")
    suspend fun get(id: String): MemoryDerivationEntity?

    @Query("SELECT * FROM memory_derivations ORDER BY createdAt, id")
    suspend fun all(): List<MemoryDerivationEntity>

    @Query("SELECT * FROM memory_derivations WHERE slotId = :slot ORDER BY id")
    suspend fun inSlot(slot: String): List<MemoryDerivationEntity>

    @Query("SELECT * FROM memory_derivations WHERE slotId = :slot ORDER BY id LIMIT :limit")
    suspend fun boundedSlot(slot: String, limit: Int): List<MemoryDerivationEntity>

    @Query("SELECT * FROM memory_derivations WHERE state = 'PENDING' ORDER BY createdAt, id LIMIT :limit")
    suspend fun pending(limit: Int): List<MemoryDerivationEntity>

    @Query("SELECT * FROM memory_derivations WHERE state = 'PENDING' AND instr(content, :keyword) > 0 ORDER BY id LIMIT 257")
    suspend fun pendingMatching(keyword: String): List<MemoryDerivationEntity>

    @Query("UPDATE memory_derivations SET state = :state, appliedAt = :now WHERE id = :id AND state = 'PENDING'")
    suspend fun finish(id: String, state: String, now: Long): Int

    @Query("SELECT DISTINCT slotId FROM memory_derivations WHERE slotId > :after ORDER BY slotId LIMIT :limit")
    suspend fun slots(after: String, limit: Int): List<String>

    @Query("SELECT DISTINCT slotId FROM memory_derivations WHERE scope = :scope ORDER BY slotId LIMIT :limit")
    suspend fun scopedSlots(scope: String, limit: Int): List<String>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putView(value: MemoryDerivationViewEntity)

    @Query("DELETE FROM memory_derivation_views WHERE slotId = :slot")
    suspend fun clearView(slot: String)

    @Query("SELECT * FROM memory_derivation_views WHERE slotId > :after ORDER BY slotId LIMIT :limit")
    suspend fun viewPage(after: String, limit: Int): List<MemoryDerivationViewEntity>
}
