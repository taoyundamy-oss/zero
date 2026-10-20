package com.zero.v2.core.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.zero.v2.core.data.entity.CharacterRelationState
import com.zero.v2.core.data.entity.CharacterStateEvent
import com.zero.v2.core.data.entity.ConversationMoodState

/**
 * 角色状态 DAO — P1（设计§6）。
 *
 * - CharacterRelationState / ConversationMoodState：1:1 upsert（characterId/conversationId PK）。
 * - CharacterStateEvent：幂等 IGNORE（unique idempotency_key）+ 有界保留（purgeOldEvents）。
 * - 角色删除经 FK CASCADE 自动清理（不在此 DAO 显式删）。
 */
@Dao
interface CharacterStateDao {

    @Transaction
    suspend fun applyRelationChange(state: CharacterRelationState, event: CharacterStateEvent) {
        if (insertEventIfNew(event) != -1L) upsertRelationState(state)
    }

    @Transaction
    suspend fun applyMoodChange(state: ConversationMoodState, event: CharacterStateEvent) {
        if (insertEventIfNew(event) != -1L) upsertMoodState(state)
    }

    // ═══ CharacterRelationState（按角色，长期）═══

    @Query("SELECT * FROM character_relation_states WHERE character_id = :characterId")
    suspend fun getRelationState(characterId: String): CharacterRelationState?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertRelationState(state: CharacterRelationState)

    @Query("DELETE FROM character_relation_states WHERE character_id = :characterId")
    suspend fun deleteRelationState(characterId: String)

    // ═══ ConversationMoodState（按会话，短期）═══

    @Query("SELECT * FROM conversation_mood_states WHERE conversation_id = :conversationId")
    suspend fun getMoodState(conversationId: String): ConversationMoodState?

    @Query("SELECT m.* FROM conversation_mood_states m JOIN conversations c ON c.id=m.conversation_id " +
        "WHERE c.character_id=:characterId ORDER BY m.updated_at DESC, m.conversation_id LIMIT 1")
    suspend fun latestMoodForCharacter(characterId: String): ConversationMoodState?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertMoodState(state: ConversationMoodState)

    @Query("DELETE FROM conversation_mood_states WHERE conversation_id = :conversationId")
    suspend fun deleteMoodState(conversationId: String)

    /** P3：按角色清理会话情绪——删该角色所有会话的 mood 状态（角色页"重置情绪"用）。 */
    @Query(
        """DELETE FROM conversation_mood_states
           WHERE conversation_id IN (SELECT id FROM conversations WHERE character_id = :characterId)"""
    )
    suspend fun deleteMoodStatesForCharacter(characterId: String)

    // ═══ CharacterStateEvent（幂等审计 + 有界保留）═══

    /** 幂等写入事件（idempotency_key 冲突 IGNORE——同源同类型不重复写）。返回 1=新插入，0=已存在忽略。 */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertEventIfNew(event: CharacterStateEvent): Long

    @Query("SELECT * FROM character_state_events WHERE character_id = :characterId ORDER BY created_at DESC LIMIT :limit")
    suspend fun recentEventsForCharacter(characterId: String, limit: Int): List<CharacterStateEvent>

    @Query("SELECT * FROM character_state_events WHERE conversation_id = :conversationId ORDER BY created_at DESC LIMIT :limit")
    suspend fun recentEventsForConversation(conversationId: String, limit: Int): List<CharacterStateEvent>

    /** 有界保留——清 :before 之前的事件（防无限增长；reducer 周期调用）。 */
    @Query("DELETE FROM character_state_events WHERE created_at < :before")
    suspend fun purgeOldEvents(before: Long): Int

    @Query("SELECT COUNT(*) FROM character_state_events WHERE character_id = :characterId")
    suspend fun countEventsForCharacter(characterId: String): Int
}
