package com.zero.v2.core.data.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.zero.v2.core.common.MemoryType

/** A user-created proposal is not evidence until explicitly confirmed. Never read by recall. */
@Entity(tableName = "memory_reviews", foreignKeys = [ForeignKey(
    entity = MemoryEntity::class, parentColumns = ["id"], childColumns = ["memoryId"],
    onDelete = ForeignKey.CASCADE,
)], indices = [Index("memoryId"), Index("resultMemoryId")])
data class MemoryReviewEntity(
    @PrimaryKey val id: String,
    val memoryId: String,
    val baseVersion: String,
    val proposedContent: String,
    val proposedType: MemoryType,
    val proposedScope: String,
    val reason: String,
    val sourceMessageId: String?,
    val sourceVersion: String?,
    val state: String = "PENDING",
    val evidenceKind: String = "USER_REVIEW_DRAFT",
    val createdAt: Long,
    val decidedAt: Long? = null,
    val resultMemoryId: String? = null,
    @androidx.room.ColumnInfo(defaultValue = "0") val editTimes: Boolean = false,
    val proposedOccurredAt: Long? = null,
    val proposedValidFrom: Long? = null,
    val proposedValidUntil: Long? = null,
)
