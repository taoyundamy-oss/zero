package com.zero.v2.core.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.zero.v2.core.data.entity.AutomationEntity

/**
 * 自动化任务 DAO — Zero v3.5（参考 CharacterDao 模式）。
 */
@Dao
interface AutomationDao {

    @Query("SELECT * FROM automations WHERE status != 'DELETED' ORDER BY createdAt DESC")
    suspend fun getActive(): List<AutomationEntity>

    @Query("SELECT * FROM automations WHERE id = :id")
    suspend fun getById(id: String): AutomationEntity?

    @Query("SELECT * FROM automations WHERE status = 'ACTIVE' AND nextRunAt <= :now")
    suspend fun getDue(now: Long): List<AutomationEntity>

    @Query("SELECT MIN(nextRunAt) FROM automations WHERE status = 'ACTIVE'")
    suspend fun getNextRunAt(): Long?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(automation: AutomationEntity)

    @Query("UPDATE automations SET status = :status WHERE id = :id")
    suspend fun setStatus(id: String, status: String)

    @Query(
        """
        UPDATE automations
        SET lastRunAt = :lastRunAt, nextRunAt = :nextRunAt, runCount = runCount + 1
        WHERE id = :id
        """
    )
    suspend fun markRun(id: String, lastRunAt: Long, nextRunAt: Long)
}
