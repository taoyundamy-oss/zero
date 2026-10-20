package com.zero.v2.core.data.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.zero.v2.core.common.MemoryStatus
import com.zero.v2.core.common.MemoryType

/**
 * 记忆条目实体（Room）— Zero v2。
 *
 * 从 v1 移植，对标 Alife 多级压缩记忆系统（L0-L8）。
 *
 * @property id 记忆唯一标识
 * @property content 记忆文本内容
 * @property embedding 向量嵌入 (FloatArray → BLOB, Phase 10+)
 * @property level 压缩等级 (L0 最原始 → L8 最压缩)
 * @property importance 重要性评分 (0.0-1.0)
 * @property sourceConversationId 来源会话 ID
 * @property keywords 关键词（逗号分隔）
 * @property createdAt 创建时间戳
 * @property accessCount 访问次数
 * @property lastAccessedAt 最后访问时间戳
 */
@Entity(
    tableName = "memories",
    indices = [
        Index(value = ["level"]),
        Index(value = ["importance"]),
        Index(value = ["source_conversation_id"]),
        Index(value = ["tier"]),
        Index(value = ["embed_model"])  // R2：有界候选查询按模型预筛
    ]
)
data class MemoryEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "content") val content: String,
    @ColumnInfo(name = "embedding") val embedding: ByteArray? = null,
    @ColumnInfo(name = "level") val level: Int = 0,
    @ColumnInfo(name = "importance") val importance: Float = 0f,
    @ColumnInfo(name = "source_conversation_id") val sourceConversationId: String? = null,
    @ColumnInfo(name = "keywords") val keywords: String? = null,
    @ColumnInfo(name = "tier") val tier: Int = 1,
    /** v4.1: 语义类型（§3.7 typeWeight 维度；Room 内建枚举→String 转换） */
    @ColumnInfo(name = "type") val type: MemoryType = MemoryType.EVENT,
    /** v4.1: 生命周期状态（归档语义） */
    @ColumnInfo(name = "status") val status: MemoryStatus = MemoryStatus.ACTIVE,
    /** v4.1: 可信度 0.0-1.0（用户明示 0.9 / 推断 0.5 量级，D25 对齐） */
    @ColumnInfo(name = "confidence") val confidence: Float = 0.5f,
    /** v4.1: 强化计数（每次命中检索 +1，§3.7 reinforcementBonus） */
    @ColumnInfo(name = "reinforcement_count") val reinforcementCount: Int = 0,
    @ColumnInfo(name = "created_at") val createdAt: Long = System.currentTimeMillis(),
    @ColumnInfo(name = "access_count") val accessCount: Int = 0,
    @ColumnInfo(name = "last_accessed_at") val lastAccessedAt: Long = System.currentTimeMillis(),
    /** R2：写入向量时的 provider.modelId（KA5 记忆域换档防护——检索按当前模型预筛，
     *  不匹配走词法降级；空串=未知模型/无向量，被有界查询排除）。与 KbChunkEntity.embedModel 同律。 */
    @ColumnInfo(name = "embed_model", defaultValue = "") val embedModel: String = "",
    @ColumnInfo(name = "origin", defaultValue = "'UNKNOWN'") val origin: String = "UNKNOWN",
    @ColumnInfo(name = "source_message_id") val sourceMessageId: String? = null,
    @ColumnInfo(name = "occurred_at") val occurredAt: Long? = null,
    @ColumnInfo(name = "valid_from") val validFrom: Long? = null,
    @ColumnInfo(name = "valid_until") val validUntil: Long? = null,
    @ColumnInfo(name = "supersedes_id") val supersedesId: String? = null,
    @ColumnInfo(name = "revision", defaultValue = "1") val revision: Int = 1,
    @ColumnInfo(name = "scope", defaultValue = "'personal'") val scope: String = "personal",
)
