package com.zero.v2.core.data.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 角色关系状态 — P1（设计§6 / D4：亲密度按角色长期保存）。
 *
 * 一角色一行（character_id PK + UNIQUE）。affinity 离散档位（String 存 AffinityBand.name，
 * 与 MemoryEntity.type 同律——Room 自动枚举转换或显式 String 列；此处用 String 列 + 调用方 enum 转
 * 换，零 TypeConverter 依赖）。无行 = 默认中性（[com.zero.v2.core.common.AffinityBand.DEFAULT]）。
 *
 * FK→characters.id CASCADE：角色删除自动清理状态（验收"角色删除清理状态"）。
 * 切换角色不串值：每角色独立行，characterId 隔离（D4）。
 */
@Entity(
    tableName = "character_relation_states",
    foreignKeys = [
        ForeignKey(entity = CharacterEntity::class, parentColumns = ["id"],
            childColumns = ["character_id"], onDelete = ForeignKey.CASCADE)
    ],
    indices = [Index(value = ["character_id"], unique = true)]
)
data class CharacterRelationState(
    @PrimaryKey @ColumnInfo(name = "character_id") val characterId: String,
    /** 亲密度档位（AffinityBand.name；空/未知→默认 ACQUAINTANCE） */
    @ColumnInfo(name = "affinity_band", defaultValue = "ACQUAINTANCE") val affinityBand: String = "ACQUAINTANCE",
    /** 累计交互计数（reducer 递增，冷却/单轮最大变化约束在 P2） */
    @ColumnInfo(name = "interaction_count", defaultValue = "0") val interactionCount: Int = 0,
    @ColumnInfo(name = "updated_at") val updatedAt: Long = System.currentTimeMillis(),
    /** 乐观并发/变更计数（reducer 每次更新 +1） */
    @ColumnInfo(name = "revision", defaultValue = "0") val revision: Int = 0,
)

/**
 * 会话情绪状态 — P1（设计§6 / D4：情绪按会话短期保存）。
 *
 * 一会话一行（conversation_id PK + UNIQUE）。mood 短时衰减到 neutral（expiresAt TTL；
 * reducer P2 衰减，无行=默认 NEUTRAL）。
 * FK→conversations.id CASCADE：会话删除自动清理状态。
 */
@Entity(
    tableName = "conversation_mood_states",
    foreignKeys = [
        ForeignKey(entity = ConversationEntity::class, parentColumns = ["id"],
            childColumns = ["conversation_id"], onDelete = ForeignKey.CASCADE)
    ],
    indices = [Index(value = ["conversation_id"], unique = true)]
)
data class ConversationMoodState(
    @PrimaryKey @ColumnInfo(name = "conversation_id") val conversationId: String,
    /** 情绪（Mood.name；空/未知→默认 NEUTRAL） */
    @ColumnInfo(name = "mood", defaultValue = "NEUTRAL") val mood: String = "NEUTRAL",
    /** 情绪强度 0-1（衰减到 0=neutral；reducer P2 衰减） */
    @ColumnInfo(name = "intensity", defaultValue = "0") val intensity: Float = 0f,
    /** TTL 过期时间戳（0=无情绪/已衰减回 neutral） */
    @ColumnInfo(name = "expires_at", defaultValue = "0") val expiresAt: Long = 0,
    @ColumnInfo(name = "updated_at") val updatedAt: Long = System.currentTimeMillis(),
    @ColumnInfo(name = "revision", defaultValue = "0") val revision: Int = 0,
)

/**
 * 状态变更事件 — P1（设计§6：限量 CharacterStateEvent，来源消息/变化原因/幂等键/时间）。
 *
 * 审计日志——reducer（P2）写入，UI（P3）读"最近变化依据"。idempotency_key UNIQUE IGNORE 幂等防重。
 * 双 FK CASCADE：角色删 + 会话删均清理（characterId 非空，conversationId 可空=跨会话亲密度变更）。
 * 有界保留：[CharacterStateDao.purgeOldEvents] 清过期事件（防无限增长）。
 */
@Entity(
    tableName = "character_state_events",
    foreignKeys = [
        ForeignKey(entity = CharacterEntity::class, parentColumns = ["id"],
            childColumns = ["character_id"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = ConversationEntity::class, parentColumns = ["id"],
            childColumns = ["conversation_id"], onDelete = ForeignKey.CASCADE)
    ],
    indices = [
        Index(value = ["character_id"]),
        Index(value = ["conversation_id"]),
        Index(value = ["idempotency_key"], unique = true),
        Index(value = ["created_at"])
    ]
)
data class CharacterStateEvent(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "character_id") val characterId: String,
    /** 可空=跨会话亲密度变更（不属特定会话） */
    @ColumnInfo(name = "conversation_id") val conversationId: String? = null,
    @ColumnInfo(name = "source_message_id") val sourceMessageId: String? = null,
    /** 变更类型：AFFINITY / MOOD（reducer P2 分类） */
    @ColumnInfo(name = "change_type") val changeType: String,
    /** 简要变更原因（用户可读，"最近变化依据"展示用） */
    @ColumnInfo(name = "reason") val reason: String,
    /** 幂等键：changeType + SHA-256(sourceMessageId|reason) 前 16 hex——同源同类型不重复写 */
    @ColumnInfo(name = "idempotency_key") val idempotencyKey: String,
    @ColumnInfo(name = "created_at") val createdAt: Long = System.currentTimeMillis(),
) {
    companion object {
        const val CHANGE_TYPE_AFFINITY = "AFFINITY"
        const val CHANGE_TYPE_MOOD = "MOOD"
    }
}
