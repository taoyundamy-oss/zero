package com.zero.v2.core.data.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 系统状态持久化实体 — v6.7（实测 P0：提醒/记事本重启与更新后丢失）。
 *
 * SystemEventModule 的 taskQueue/notepad 原为纯内存态，进程重建即失。
 */
@Entity(tableName = "notepad")
data class NotepadEntity(
    @PrimaryKey val id: Int = 1,  // 单行
    @ColumnInfo(name = "content") val content: String,
    @ColumnInfo(name = "updated_at") val updatedAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "reminders")
data class ReminderEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "remark") val remark: String,
    @ColumnInfo(name = "trigger_at") val triggerAt: Long,
    @ColumnInfo(name = "is_recurring") val isRecurring: Boolean = false,
    @ColumnInfo(name = "recurring_ms") val recurringMs: Long = 0,
    // ═══ S3.1 扩列（收束轨 D118 · DB v14）：全生命周期字段 ═══
    // 旧字段（remark/trigger_at/is_recurring/recurring_ms）保留兼容——既有写入方零改动
    /** 展示标题（缺省回落 remark——迁移期旧数据 remark 即标题） */
    @ColumnInfo(name = "title", defaultValue = "") val title: String = "",
    /** 补充说明（可空） */
    @ColumnInfo(name = "note") val note: String? = null,
    /** 生命周期：active / missed / done（G6：一次性过期→missed；周期过期→重排永不 missed） */
    @ColumnInfo(name = "status", defaultValue = STATUS_ACTIVE) val status: String = STATUS_ACTIVE,
    /** 创建者：user / llm（S3.3 FC 标记） */
    @ColumnInfo(name = "created_by", defaultValue = CREATED_BY_USER) val createdBy: String = CREATED_BY_USER,
    /** 周期规则：null=一次性；"daily"/"weekly"/"monthly"/"interval:毫秒"（与 is_recurring/recurring_ms 并存，语义权威） */
    @ColumnInfo(name = "repeat_rule") val repeatRule: String? = null
) {
    companion object {
        const val STATUS_ACTIVE = "active"
        const val STATUS_MISSED = "missed"
        const val STATUS_DONE = "done"
        const val CREATED_BY_USER = "user"
        const val CREATED_BY_LLM = "llm"
    }
}
