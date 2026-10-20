package com.zero.v2.core.data.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.zero.v2.core.data.entity.DynamicModuleEntity
import kotlinx.coroutines.flow.Flow

/**
 * 动态模块 DAO —— M2.1（§5.2）。
 * upsert 幂等（同名 develop_module=更新该模块，指引 §五）；B8 纪律不涉——主键显式、无 UNIQUE 撞删路径。
 */
@Dao
interface DynamicModuleDao {

    @Upsert
    suspend fun upsert(module: DynamicModuleEntity)

    @Query("SELECT * FROM dynamic_modules ORDER BY created_at DESC")
    fun observeAll(): Flow<List<DynamicModuleEntity>>

    @Query("SELECT * FROM dynamic_modules WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): DynamicModuleEntity?

    @Query("SELECT * FROM dynamic_modules WHERE state = 'ACTIVE'")
    suspend fun getEnabled(): List<DynamicModuleEntity>

    /** M4.1：崩溃自动停清单（DISABLED 且有崩溃计数——权限中心告警行数据源） */
    @Query("SELECT * FROM dynamic_modules WHERE state = 'DISABLED' AND crash_count > 0 ORDER BY last_crash_at DESC")
    suspend fun getAutoDisabled(): List<DynamicModuleEntity>

    @Query("DELETE FROM dynamic_modules WHERE id = :id")
    suspend fun delete(id: String)
}
