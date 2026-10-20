package com.zero.v2.core.data.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** Counts only. No prompts, request bodies, endpoint URLs or credentials. */
@Entity(tableName = "usage_requests", indices = [Index("startedAt")])
data class UsageRequestEntity(
    @PrimaryKey val id: String,
    val startedAt: Long,
    val finishedAt: Long? = null,
    val connectionId: String,
    val profileId: String,
    val model: String,
    val kind: String,
    val status: String = "prepared",
    val inputTokens: Long? = null,
    val cachedTokens: Long? = null,
    val outputTokens: Long? = null,
    val continuity: String = "unknown",
    val budgetSource: String = "unknown",
    val local: Boolean = false,
)
