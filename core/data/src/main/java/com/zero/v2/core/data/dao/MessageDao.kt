package com.zero.v2.core.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import androidx.room.Transaction
import com.zero.v2.core.data.entity.MessageEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface MessageDao : ChronicleSourceWriter {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertNew(messages: List<MessageEntity>): List<Long>

    @Query("UPDATE conversations SET message_count = (SELECT COUNT(*) FROM messages WHERE conversation_id = :id), updated_at = :now WHERE id = :id")
    suspend fun refreshMessageCount(id: String, now: Long)

    /** Idempotent append in transcript order; timestamp ties cannot reorder FC messages after restart. */
    @Transaction
    suspend fun appendTurn(conversationId: String, messages: List<MessageEntity>): Int {
        require(messages.all { it.conversationId == conversationId })
        val now = System.currentTimeMillis()
        val start = maxOf(now, getLastMessage(conversationId)?.createdAt ?: 0L)
        val values = messages.mapIndexed { index, message -> message.copy(createdAt = start + index + 1) }
        val inserted = insertNew(values)
        values.zip(inserted).filter { it.second != -1L }.forEach { (value, _) ->
            if (!hasAnchor("message", value.id)) {
                com.zero.v2.core.data.entity.ChronicleSourceEntity.message(value, now)?.let { retainSource(it) }
            }
        }
        refreshMessageCount(conversationId, now)
        return inserted.count { it != -1L }
    }

    /** Atomically replace this user's entire reply/tool chain, keeping later turns in order. */
    @Transaction
    suspend fun replaceReplyTurn(conversationId: String, userId: String, replies: List<MessageEntity>): Boolean {
        if (replies.isEmpty() || replies.first().role != "assistant") return false
        require(replies.all { it.conversationId == conversationId && it.role in setOf("assistant", "tool") })
        val ids = replies.map { it.id }
        if (ids.distinct().size != ids.size || countForeignIds(conversationId, ids) > 0) return false
        val rows = getByConversation(conversationId)
        val userIndex = rows.indexOfFirst { it.id == userId && it.role == "user" }
        if (userIndex < 0) return false
        val nextUser = ((userIndex + 1) until rows.size).firstOrNull { rows[it].role == "user" } ?: rows.size
        val end = if (nextUser < rows.size && rows[nextUser - 1].id == com.zero.v2.core.common.TurnHistory.contextId(rows[nextUser].id)) {
            nextUser - 1
        } else nextUser
        val oldReplies = rows.subList(userIndex + 1, end)
        if (oldReplies.any { it.role !in setOf("assistant", "tool") }) return false
        val ownedIds = oldReplies.map { it.id }.toSet()
        if (rows.any { it.id in ids && it.id !in ownedIds }) return false
        var cursor = rows[userIndex].createdAt
        val replacement = replies.map { reply ->
            val old = oldReplies.firstOrNull { it.id == reply.id }
            cursor = maxOf(cursor + 1, old?.createdAt ?: cursor + 1)
            (old ?: reply).copy(role = reply.role, content = reply.content, toolCalls = reply.toolCalls,
                attachments = reply.attachments, source = reply.source, systemSource = reply.systemSource,
                triggerId = reply.triggerId, createdAt = cursor)
        }
        // Extra FC messages need integer timestamp slots; shift the later suffix only when necessary.
        val later = rows.drop(end)
        val shift = later.firstOrNull()?.let { (cursor + 1 - it.createdAt).coerceAtLeast(0) } ?: 0
        if (shift > 0) later.forEach { updateExisting(it.copy(createdAt = it.createdAt + shift)) }
        deleteMessages(ownedIds.toList())
        check(insertNew(replacement).all { it != -1L }) { "Reply IDs changed ownership during replacement" }
        refreshMessageCount(conversationId, System.currentTimeMillis())
        return true
    }

    @Query("SELECT COUNT(*) FROM messages WHERE id IN (:ids) AND conversation_id != :conversationId")
    suspend fun countForeignIds(conversationId: String, ids: List<String>): Int

    @Query("DELETE FROM messages WHERE id IN (:ids)")
    suspend fun deleteMessages(ids: List<String>)

    @Update
    suspend fun updateExisting(message: MessageEntity)

    @Query("SELECT * FROM messages WHERE conversation_id = :conversationId ORDER BY created_at ASC")
    fun observeByConversation(conversationId: String): Flow<List<MessageEntity>>

    @Query("SELECT * FROM messages WHERE conversation_id = :conversationId ORDER BY created_at ASC")
    suspend fun getByConversation(conversationId: String): List<MessageEntity>

    @Query("SELECT * FROM messages WHERE conversation_id = :conversationId ORDER BY created_at DESC LIMIT 1")
    suspend fun getLastMessage(conversationId: String): MessageEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(message: MessageEntity)

    /** New user input only. Backup and transcript loading keep using non-capturing insert APIs. */
    @Transaction
    suspend fun insertWithSource(message: MessageEntity) {
        check(insertNew(listOf(message)).single() != -1L) { "message_id_already_exists" }
        if (!hasAnchor("message", message.id)) {
            com.zero.v2.core.data.entity.ChronicleSourceEntity.message(message, System.currentTimeMillis())?.let { retainSource(it) }
        }
    }

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(messages: List<MessageEntity>)

    @Query("DELETE FROM messages WHERE id = :id")
    suspend fun delete(id: String)

    @Query("DELETE FROM messages WHERE conversation_id = :conversationId")
    suspend fun deleteByConversation(conversationId: String)

    @Query("SELECT COUNT(*) FROM messages WHERE conversation_id = :conversationId")
    suspend fun countByConversation(conversationId: String): Int

    @Query("SELECT COUNT(*) FROM messages")
    suspend fun count(): Int
}
