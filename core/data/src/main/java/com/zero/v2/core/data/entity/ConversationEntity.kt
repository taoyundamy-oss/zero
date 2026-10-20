package com.zero.v2.core.data.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 对话实体（Room）— Zero v2。
 *
 * 一个 Conversation 包含多条 Message，对应一次完整的对话会话。
 *
 * @property id 对话唯一标识（UUID）
 * @property title 对话标题（默认使用首条消息摘要）
 * @property characterId 关联的角色 ID，null 表示使用默认角色
 * @property createdAt 创建时间戳 (epoch millis)
 * @property updatedAt 最后更新时间戳 (epoch millis)
 * @property messageCount 消息总数
 */
@Entity(
    tableName = "conversations",
    indices = [
        Index(value = ["updated_at"])
    ]
)
data class ConversationEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "title") val title: String,
    @ColumnInfo(name = "character_id") val characterId: String? = null,
    @ColumnInfo(name = "created_at") val createdAt: Long = System.currentTimeMillis(),
    @ColumnInfo(name = "updated_at") val updatedAt: Long = System.currentTimeMillis(),
    @ColumnInfo(name = "message_count") val messageCount: Int = 0,
    /** N6.2（设计 §九 CC2）：会话滚动摘要（早期对话 LLM 压缩产物，system 注入用；null=未生成） */
    @ColumnInfo(name = "summary") val summary: String? = null,
    /** N6.2：摘要水印=已压缩至的消息 id（X13：消息 id 而非 timestamp——重载后时间戳不保真） */
    @ColumnInfo(name = "summary_upto") val summaryUpto: String? = null
)
