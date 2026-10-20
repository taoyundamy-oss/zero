package com.zero.v2.core.data.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 消息实体（Room）— Zero v2。
 *
 * 一条 Message 属于一个 Conversation，代表一轮对话中的一条消息。
 *
 * @property id 消息唯一标识（UUID）
 * @property conversationId 所属对话 ID
 * @property role 消息角色: "user" / "assistant" / "system" / "tool"
 * @property content 消息文本内容，tool 角色的结果可为 null
 * @property toolCalls LLM 返回的 tool_calls JSON（Phase 2+ 使用）
 * @property automationRunId 关联的自动化运行 ID（Phase 3+ 使用）
 * @property tokensUsed 此消息消耗的 token 数
 * @property attachments 附件 JSON 数组
 * @property createdAt 创建时间戳 (epoch millis)
 * @property source 消息来源（MessageSource 枚举名，S2R.2）——自驱注入消息=AUTONOMOUS；null=存量行/普通消息
 * @property systemSource 系统注入来源细名（TriggerEvent.source，S2R.2）——仅自驱/事件注入携带
 * @property triggerId 触发事件 id（TriggerEvent.id，S2R.2）——留痕↔RunLog 审计链关联（F7）
 */
@Entity(
    tableName = "messages",
    foreignKeys = [
        ForeignKey(
            entity = ConversationEntity::class,
            parentColumns = ["id"],
            childColumns = ["conversation_id"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["conversation_id"]),
        Index(value = ["created_at"])
    ]
)
data class MessageEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "conversation_id") val conversationId: String,
    @ColumnInfo(name = "role") val role: String,
    @ColumnInfo(name = "content") val content: String? = null,
    @ColumnInfo(name = "tool_calls") val toolCalls: String? = null,
    @ColumnInfo(name = "automation_run_id") val automationRunId: String? = null,
    @ColumnInfo(name = "tokens_used") val tokensUsed: Long? = null,
    @ColumnInfo(name = "attachments") val attachments: String? = null,
    @ColumnInfo(name = "created_at") val createdAt: Long = System.currentTimeMillis(),
    // ═══ S2R.2（DB v21 · 自驱留痕三列 §2.3）——全可空，存量行 NULL=普通消息，老库升级零迁移数据 ═══
    @ColumnInfo(name = "source") val source: String? = null,
    @ColumnInfo(name = "system_source") val systemSource: String? = null,
    @ColumnInfo(name = "trigger_id") val triggerId: String? = null
)
