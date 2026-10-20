package com.zero.v2.core.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.zero.v2.core.data.entity.NotepadEntity
import com.zero.v2.core.data.entity.ReminderEntity

/** 系统状态 DAO — v6.7 提醒/记事本持久化（DB v12）。 */
@Dao
interface SystemStateDao {

    // ═══ 记事本（单行）═══
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveNotepad(entity: NotepadEntity)

    @Query("SELECT content FROM notepad WHERE id = 1")
    suspend fun loadNotepad(): String?

    // ═══ 提醒（S3.1 v14 扩列：状态/周期生命周期）═══
    @Transaction
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveReminder(entity: ReminderEntity)

    @Query("SELECT * FROM reminders")
    suspend fun loadReminders(): List<ReminderEntity>

    @Query("SELECT * FROM reminders WHERE status = 'active'")
    suspend fun loadActiveReminders(): List<ReminderEntity>

    @Query("UPDATE reminders SET status = :status WHERE id = :id")
    suspend fun updateStatus(id: String, status: String)

    /** 全字段排程更新（S3.3 modify_reminder / G6 周期重排 / missed 重排共用）。
     *  2026-09-18 真机反馈修复：补 remark 列——原 SQL 漏写致 modify_reminder 改文本静默失败
     *  （回显拼入参"看似成功"，list 从 DB 读仍是旧文——重排调用方传原 remark 保持不变）。 */
    @Query("UPDATE reminders SET trigger_at = :triggerAt, repeat_rule = :repeatRule, " +
            "is_recurring = :isRecurring, recurring_ms = :recurringMs, status = :status, " +
            "remark = :remark WHERE id = :id")
    suspend fun updateSchedule(id: String, triggerAt: Long, repeatRule: String?,
                               isRecurring: Boolean, recurringMs: Long, status: String,
                               remark: String)

    @Transaction
    @Query("DELETE FROM reminders WHERE id = :id")
    suspend fun deleteReminder(id: String)

    @Transaction
    @Query("DELETE FROM reminders WHERE id = :id AND status = 'active' AND trigger_at = :expectedTriggerAt")
    suspend fun deleteDeliveredIfActive(id: String, expectedTriggerAt: Long): Int

    /** Preserve a failed occurrence for explicit recovery without overwriting a newer schedule. */
    @Transaction
    @Query("UPDATE reminders SET status = 'missed' WHERE id = :id AND status = 'active' " +
            "AND trigger_at = :expectedTriggerAt")
    suspend fun markUndeliveredIfActive(id: String, expectedTriggerAt: Long): Int

    /** Advance only the active schedule that was claimed, retaining all other entity fields. */
    @Transaction
    @Query("UPDATE reminders SET trigger_at = :nextTriggerAt " +
            "WHERE id = :id AND status = 'active' AND trigger_at = :expectedTriggerAt")
    suspend fun advanceRecurringIfActive(id: String, expectedTriggerAt: Long, nextTriggerAt: Long): Int
}
