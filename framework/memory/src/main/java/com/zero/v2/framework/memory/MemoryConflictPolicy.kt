package com.zero.v2.framework.memory

import com.zero.v2.core.common.MemoryStatus
import com.zero.v2.core.common.MemoryType
import com.zero.v2.core.data.entity.MemoryEntity

/** Automatic consolidation cannot establish that a changed user statement is obsolete. */
internal object MemoryConflictPolicy {
    fun ordered(a: MemoryEntity, b: MemoryEntity): Pair<MemoryEntity, MemoryEntity> =
        if (compareValuesBy(a, b, { it.createdAt }, { it.id }) >= 0) a to b else b to a

    fun eligible(a: MemoryEntity, b: MemoryEntity): Boolean =
        a.id != b.id && a.scope == b.scope && a.type == b.type && listOf(a, b).all {
            it.status == MemoryStatus.ACTIVE && it.type in setOf(MemoryType.FACT, MemoryType.PREFERENCE, MemoryType.DIRECTIVE)
        }

    fun canConsolidate(a: MemoryEntity, b: MemoryEntity): Boolean =
        eligible(a, b) && a.type == MemoryType.FACT && a.content == b.content && a.origin == "USER_QUOTE" && a.origin == b.origin &&
            !a.sourceMessageId.isNullOrBlank() && !a.sourceConversationId.isNullOrBlank() &&
            a.scope == b.scope && a.sourceMessageId == b.sourceMessageId &&
            a.sourceConversationId == b.sourceConversationId && a.validFrom == b.validFrom &&
            a.validUntil == b.validUntil && a.occurredAt == b.occurredAt && a.confidence >= b.confidence
}
