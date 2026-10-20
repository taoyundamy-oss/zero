package com.zero.v2.core.data.dao

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.zero.v2.core.data.entity.ConversationEntity
import kotlinx.coroutines.flow.Flow

/** N6.2：会话摘要投影行（framework/chat 经 Dao 直读——X12 依赖方向，不走 app 层 Repository） */
data class ConversationSummaryRow(
    val summary: String?,
    @ColumnInfo(name = "summary_upto") val summaryUpto: String?
)

@Dao
interface ConversationDao {
    @androidx.room.Upsert
    suspend fun mergeImported(conversation: ConversationEntity)

    @Query("UPDATE conversations SET message_count = (SELECT COUNT(*) FROM messages WHERE conversation_id = :id) WHERE id = :id")
    suspend fun recountImportedMessages(id: String)

    @Query("SELECT summary, summary_upto FROM conversations WHERE id = :id")
    suspend fun getSummaryRow(id: String): ConversationSummaryRow?

    @Query("UPDATE conversations SET summary = :summary, summary_upto = :summaryUpto WHERE id = :id")
    suspend fun updateSummary(id: String, summary: String, summaryUpto: String)

    @Query("SELECT * FROM conversations ORDER BY updated_at DESC")
    fun observeAll(): Flow<List<ConversationEntity>>

    @Query("SELECT * FROM conversations ORDER BY updated_at DESC")
    suspend fun getAll(): List<ConversationEntity>

    @Query("SELECT * FROM conversations WHERE id = :id")
    suspend fun getById(id: String): ConversationEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(conversation: ConversationEntity)

    @Query(
        """
        UPDATE conversations
        SET title = :title, updated_at = :updatedAt, message_count = :messageCount
        WHERE id = :id
        """
    )
    suspend fun update(id: String, title: String, updatedAt: Long, messageCount: Int)

    @Query("UPDATE conversations SET updated_at = :updatedAt, message_count = message_count + 1 WHERE id = :id")
    suspend fun incrementMessageCount(id: String, updatedAt: Long = System.currentTimeMillis())

    @Query("DELETE FROM conversations WHERE id = :id")
    suspend fun delete(id: String)

    @Query("SELECT COUNT(*) FROM conversations")
    suspend fun count(): Int
}
