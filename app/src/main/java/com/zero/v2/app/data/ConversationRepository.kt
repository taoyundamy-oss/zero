package com.zero.v2.app.data

import com.zero.v2.core.common.Attachment
import com.zero.v2.core.common.AttachmentCodec
import com.zero.v2.core.common.ChatMessage
import com.zero.v2.core.common.MessageRole
import com.zero.v2.core.common.MessageSource
import com.zero.v2.core.data.dao.ConversationDao
import com.zero.v2.core.data.dao.MessageDao
import com.zero.v2.core.data.entity.ConversationEntity
import com.zero.v2.core.data.entity.MessageEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 对话持久化仓库 — Zero v2。
 *
 * 封装 ConversationDao + MessageDao 的联合操作。
 * 负责创建对话、追加消息、加载历史等跨 DAO 协调逻辑。
 */
@Singleton
class ConversationRepository @Inject constructor(
    private val conversationDao: ConversationDao,
    private val messageDao: MessageDao
) {
    private val turnWriteMutex = Mutex()

    suspend fun persistTurn(conversationId: String, history: List<ChatMessage>, userId: String): Int = turnWriteMutex.withLock {
        val turn = com.zero.v2.core.common.TurnHistory.slice(history, userId)
        messageDao.appendTurn(conversationId, turn.map { message ->
            MessageEntity(
                id = message.id, conversationId = conversationId, role = message.role.name.lowercase(),
                content = message.content, toolCalls = StoredMessageCodec.encode(message),
                attachments = message.attachments.takeIf { it.isNotEmpty() }?.let(AttachmentCodec::encode),
                source = message.source.name, systemSource = message.systemSource, triggerId = message.triggerId,
            )
        })
    }
    suspend fun persistRegeneratedReply(history: com.zero.v2.framework.chat.ChatHistory, userId: String): Boolean = turnWriteMutex.withLock {
        val turn = com.zero.v2.core.common.TurnHistory.slice(history.messages, userId)
        val replies = turn.filter { it.role == MessageRole.ASSISTANT || it.role == MessageRole.TOOL }
        messageDao.replaceReplyTurn(history.id, userId, replies.map { reply ->
            MessageEntity(id = reply.id, conversationId = history.id, role = reply.role.name.lowercase(),
                content = reply.content, toolCalls = StoredMessageCodec.encode(reply),
                attachments = reply.attachments.takeIf { it.isNotEmpty() }?.let(AttachmentCodec::encode),
                source = reply.source.name, systemSource = reply.systemSource, triggerId = reply.triggerId)
        })
    }

    // ═══ Conversation ═══

    /** 观察所有对话（按更新时间倒序） */
    fun observeConversations(): Flow<List<ConversationEntity>> =
        conversationDao.observeAll()

    /** 获取所有对话 */
    suspend fun getConversations(): List<ConversationEntity> =
        conversationDao.getAll()

    /** 按 ID 获取单个对话 */
    suspend fun getConversation(id: String): ConversationEntity? =
        conversationDao.getById(id)

    /** 创建新对话，返回生成的对话 ID */
    suspend fun createConversation(title: String, characterId: String? = null): String {
        val now = System.currentTimeMillis()
        val conversation = ConversationEntity(
            id = UUID.randomUUID().toString(),
            title = title,
            characterId = characterId,
            createdAt = now,
            updatedAt = now
        )
        conversationDao.insert(conversation)
        return conversation.id
    }

    /** 更新对话标题 */
    suspend fun updateTitle(conversationId: String, title: String) {
        val conversation = conversationDao.getById(conversationId) ?: return
        conversationDao.update(
            id = conversationId,
            title = title,
            updatedAt = System.currentTimeMillis(),
            messageCount = conversation.messageCount
        )
    }

    /** 删除对话（外键 CASCADE 自动删除关联消息） */
    suspend fun deleteConversation(conversationId: String) {
        conversationDao.delete(conversationId)
    }

    // ═══ Message ═══

    /** 观察指定对话的消息流 */
    fun observeMessages(conversationId: String): Flow<List<MessageEntity>> =
        messageDao.observeByConversation(conversationId)

    /** 获取指定对话的所有消息 */
    suspend fun getMessages(conversationId: String): List<MessageEntity> =
        messageDao.getByConversation(conversationId)

    /**
     * 追加一条消息，同时更新对话的 messageCount 和 updatedAt。
     * S2R.2：source/systemSource/triggerId 三可空参（自驱注入消息携带——§2.3 消息模型）；
     * 既有调用点（user/assistant/tool）零改动（默认 null=普通消息）。
     */
    suspend fun appendMessage(
        conversationId: String,
        role: String,
        content: String?,
        source: String? = null,
        systemSource: String? = null,
        triggerId: String? = null,
        attachments: List<Attachment> = emptyList()
    ): String {
        val message = MessageEntity(
            id = UUID.randomUUID().toString(),
            conversationId = conversationId,
            role = role,
            content = content,
            attachments = if (attachments.isEmpty()) null else AttachmentCodec.encode(attachments),
            source = source,
            systemSource = systemSource,
            triggerId = triggerId
        )
        turnWriteMutex.withLock { messageDao.insertWithSource(message) }
        conversationDao.incrementMessageCount(conversationId)
        return message.id
    }

    /**
     * S2R.2 回读映射：MessageEntity → ChatMessage（source/systemSource/triggerId 三新列恢复）。
     * role 枚举解析失败回 null（调用方 mapNotNull 过滤——loadConversationInternal 既有契约）；
     * source 列为存量 null 或非法值 → 回退 CHAT_WINDOW（普通消息渲染路径不变）。
     * S2R.4 接线消费（重启留痕可见——系统卡按 systemSource 三色渲染）。
     */
    fun toChatMessage(e: MessageEntity): ChatMessage? {
        return try {
            StoredMessageCodec.restore(ChatMessage(
                id = e.id,
                role = MessageRole.valueOf(e.role.uppercase()),
                content = e.content,
                // M1：恢复附件元信息（uri/mime/type，无 base64——按引用读取，不入库）
                attachments = AttachmentCodec.decode(e.attachments),
                source = e.source?.let { MessageSource.valueOf(it) } ?: MessageSource.CHAT_WINDOW,
                systemSource = e.systemSource,
                triggerId = e.triggerId,
                timestamp = e.createdAt,
            ), e.toolCalls)
        } catch (_: Exception) {
            null
        }
    }

    /** 批量追加消息（如加载历史对话时） */
    suspend fun appendMessages(messages: List<MessageEntity>) {
        messageDao.insertAll(messages)
    }

    /** 删除单条消息 */
    suspend fun deleteMessage(messageId: String) {
        messageDao.delete(messageId)
    }

    // ═══ Combined ═══

    /** 对话总数 */
    suspend fun conversationCount(): Int = conversationDao.count()

    /** 消息总数 */
    suspend fun messageCount(): Int = messageDao.count()
}
