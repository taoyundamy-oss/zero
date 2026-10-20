package com.zero.v2.core.data.entity

/** One read transaction; no access counters, certification, or memory writes. */
data class MemoryEvidenceSnapshot(
    val memory: MemoryEntity,
    val source: MessageEntity?,
    val pendingConflict: Boolean,
    val activeReplacement: Boolean,
    val confirmation: MemoryReviewEntity? = null,
    val confirmationParentVersion: String? = null,
    val confirmationLinked: Boolean = false,
)
