package com.zero.v2.core.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.zero.v2.core.data.entity.CharacterEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface CharacterDao {
    /** Validate before clearing the old role, and commit both writes atomically. */
    @Transaction
    suspend fun activateExisting(id: String): CharacterEntity {
        val target = requireNotNull(getById(id)) { "角色不存在，请重新读取" }
        deactivateAll()
        setActive(id)
        return target.copy(isActive = true)
    }

    @Transaction
    suspend fun ensureActive(): CharacterEntity? {
        getActive()?.let { return it }
        val first = getAll().firstOrNull() ?: return null
        return activateExisting(first.id)
    }

    @Transaction
    suspend fun updateExisting(id: String, name: String, description: String?, systemPrompt: String, updatedAt: Long) {
        requireNotNull(getById(id)) { "角色不存在，请重新读取" }
        update(id, name, description, systemPrompt, updatedAt)
    }

    @Transaction
    suspend fun deleteUserCharacter(id: String) {
        val current = requireNotNull(getById(id)) { "角色不存在，请重新读取" }
        require(!current.isPreset) { "预设角色不能删除" }
        delete(id)
    }

    @Transaction
    suspend fun insertPresetsIfEmpty(presets: List<CharacterEntity>) {
        if (count() == 0) presets.forEach { insert(it) }
    }

    @Query("SELECT * FROM characters ORDER BY is_preset DESC, updated_at DESC")
    fun observeAll(): Flow<List<CharacterEntity>>

    @Query("SELECT * FROM characters ORDER BY is_preset DESC, updated_at DESC")
    suspend fun getAll(): List<CharacterEntity>

    @Query("SELECT * FROM characters WHERE id = :id")
    suspend fun getById(id: String): CharacterEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(character: CharacterEntity)

    @Query(
        """
        UPDATE characters
        SET name = :name, description = :description, system_prompt = :systemPrompt, updated_at = :updatedAt
        WHERE id = :id
        """
    )
    suspend fun update(id: String, name: String, description: String?, systemPrompt: String, updatedAt: Long)

    @Query("DELETE FROM characters WHERE id = :id")
    suspend fun delete(id: String)

    @Query("SELECT COUNT(*) FROM characters")
    suspend fun count(): Int

    @Query("SELECT * FROM characters WHERE is_active = 1 LIMIT 1")
    suspend fun getActive(): CharacterEntity?

    @Query("UPDATE characters SET is_active = 0")
    suspend fun deactivateAll()

    @Query("UPDATE characters SET is_active = 1 WHERE id = :id")
    suspend fun setActive(id: String)
}
