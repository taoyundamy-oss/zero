package com.zero.v2.core.data.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.security.MessageDigest

/**
 * 记忆提取作业 outbox（R1 / 设计§5.1）——替代 MemoryModule.pendingAiBuffer 易失队列。
 *
 * 事务写入 + 唯一键（unique_key UNIQUE）幂等避免重复；进程恢复续跑不丢任务；
 * 失败退避（attempts + next_attempt_at）；有界保留（purgeCompleted 清已完结）。
 * 提取只接受可复核文本事实/偏好；图/音频转写仅作候选来源（source_type 标记）。
 */
@Entity(
    tableName = "memory_extraction_jobs",
    indices = [
        Index(value = ["unique_key"], unique = true),
        Index(value = ["state"]),
        Index(value = ["next_attempt_at"]),
    ],
)
data class MemoryExtractionJobEntity(
    @PrimaryKey val id: String,
    /** 来源类型：AI_DEFERRED（AI 回复延迟提炼）/ USER_FACT（用户事实）/ TRANSCRIPT（图/音频转写候选） */
    @ColumnInfo(name = "source_type") val sourceType: String,
    @ColumnInfo(name = "conversation_id") val conversationId: String? = null,
    @ColumnInfo(name = "source_message_id") val sourceMessageId: String? = null,
    /** 用户原始输入（新 USER_FACT ≤4000 字，不截断；旧来源保留供审计）。 */
    @ColumnInfo(name = "source_digest") val sourceDigest: String,
    /** 幂等键：sourceType + SHA-256(sourceDigest) —— 同内容不重复入队 */
    @ColumnInfo(name = "unique_key") val uniqueKey: String,
    /** PENDING / RUNNING / SUCCEEDED / FAILED / SUPERSEDED */
    @ColumnInfo(name = "state") val state: String = STATE_PENDING,
    @ColumnInfo(name = "attempts") val attempts: Int = 0,
    @ColumnInfo(name = "last_error") val lastError: String? = null,
    @ColumnInfo(name = "created_at") val createdAt: Long = System.currentTimeMillis(),
    @ColumnInfo(name = "updated_at") val updatedAt: Long = System.currentTimeMillis(),
    /** 退避调度：下次可重试时间戳（0=立即可试） */
    @ColumnInfo(name = "next_attempt_at") val nextAttemptAt: Long = 0,
) {
    companion object {
        const val STATE_PENDING = "PENDING"
        const val STATE_RUNNING = "RUNNING"
        const val STATE_SUCCEEDED = "SUCCEEDED"
        const val STATE_FAILED = "FAILED"
        const val STATE_SUPERSEDED = "SUPERSEDED"

        const val SOURCE_TYPE_AI_DEFERRED = "AI_DEFERRED"
        const val SOURCE_TYPE_USER_FACT = "USER_FACT"
        const val SOURCE_TYPE_TRANSCRIPT = "TRANSCRIPT"

        /** 最多重试次数（超则标 FAILED 不再自动重试） */
        const val MAX_ATTEMPTS = 5
    }
}

/**
 * 记忆提取作业幂等键计算（R1 纯逻辑，JVM 可测）。
 * uniqueKey = sourceType + ":" + SHA-256(sourceDigest) 前 16 hex——同内容同源必同键。
 */
object MemoryExtractionJobKey {
    private val HEX = "0123456789abcdef"

    fun compute(sourceType: String, digest: String): String {
        val md = MessageDigest.getInstance("SHA-256")
        val bytes = md.digest(digest.toByteArray(Charsets.UTF_8))
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) { val v = b.toInt() and 0xff; sb.append(HEX[v ushr 4]); sb.append(HEX[v and 0x0f]) }
        return "$sourceType:${sb.substring(0, 16)}"
    }

    /** 退避：第 n 次失败后的下次重试间隔（ms）—— 2^n 指数，封顶 1h。 */
    fun backoffMs(attempts: Int): Long {
        val base = 1_000L shl attempts.coerceAtMost(11)  // 1s,2s,4s...1024s（~17min）
        return base.coerceAtMost(3_600_000L)
    }
}
