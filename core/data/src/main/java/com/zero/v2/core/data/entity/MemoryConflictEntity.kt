package com.zero.v2.core.data.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.security.MessageDigest

@Entity(tableName = "memory_conflicts")
data class MemoryConflictEntity(
    @PrimaryKey val id: String,
    val leftId: String, val rightId: String,
    val leftVersion: String, val rightVersion: String,
    val judgment: String, val state: String = "PENDING",
    val createdAt: Long, val resolvedAt: Long? = null,
) {
    companion object {
        fun version(m: MemoryEntity): String = hash(listOf(m.id, m.content, m.revision.toString(),
            m.origin, m.type.name, m.scope, m.sourceMessageId.orEmpty(), m.sourceConversationId.orEmpty(),
            m.occurredAt.toString(), m.validFrom.toString(), m.validUntil.toString(), m.supersedesId.orEmpty())
            .joinToString("") { "${it.length}:$it" })
        fun candidate(a: MemoryEntity, b: MemoryEntity, judgment: String, now: Long): MemoryConflictEntity {
            val (left, right) = listOf(a, b).sortedBy { it.id }
            val lv = version(left); val rv = version(right)
            return MemoryConflictEntity(hash(lv + rv), left.id, right.id, lv, rv, judgment, createdAt = now)
        }
        private fun hash(value: String) = MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }
}
