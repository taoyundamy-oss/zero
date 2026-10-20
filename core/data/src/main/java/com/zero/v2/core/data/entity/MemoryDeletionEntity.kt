package com.zero.v2.core.data.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/** No deleted content is retained; prevents stale jobs/backups from recreating the same identity. */
@Entity(tableName = "memory_deletions")
data class MemoryDeletionEntity(@PrimaryKey val id: String, val deletedAt: Long)
