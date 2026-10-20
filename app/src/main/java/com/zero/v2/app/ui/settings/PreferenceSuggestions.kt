package com.zero.v2.app.ui.settings

import com.zero.v2.core.common.MemoryStatus
import com.zero.v2.core.common.MemoryType
import com.zero.v2.core.data.entity.MemoryEntity
import com.zero.v2.core.data.entity.MessageEntity
import com.zero.v2.core.data.entity.MemoryConflictEntity
import com.zero.v2.services.retrieval.EvidenceEligibility

/** Read-only suggestions. Repetition is a review hint, never proof of a generalized preference. */
object PreferenceSuggestions {
    data class Candidate(val memory: MemoryEntity, val supportingMessages: List<String>)

    fun generate(memories: List<MemoryEntity>, messages: Map<String, MessageEntity>, now: Long,
        reviewedVersions: Set<String> = emptySet(), conflicts: Set<String> = emptySet()): List<Candidate> =
        memories.filter { m ->
            m.type == MemoryType.PREFERENCE && eligible(m, messages[m.sourceMessageId], now, conflicts)
        }.groupBy { it.content.trim() to it.scope }
            .mapNotNull { (_, rows) ->
                val sources = rows.mapNotNull { it.sourceMessageId }.distinct().sorted()
                if (sources.size < 2 || rows.any { MemoryConflictEntity.version(it) in reviewedVersions }) null else Candidate(rows.maxWith(compareBy<MemoryEntity> { it.createdAt }.thenBy { it.id }), sources)
            }.sortedBy { it.memory.id }.take(20)
    private fun eligible(m: MemoryEntity, source: MessageEntity?, now: Long, conflicts: Set<String>): Boolean {
        val verified = m.origin == "USER_QUOTE" && source != null && source.role == "user" &&
            source.systemSource == null && source.triggerId == null &&
            source.source in setOf("CHAT_WINDOW", "VOICE", "REMOTE") &&
            source.conversationId == m.sourceConversationId && source.content?.trim() == m.content.trim()
        val evidence = EvidenceEligibility.Source(active = m.status == MemoryStatus.ACTIVE,
            validFrom = m.validFrom, validUntil = m.validUntil, sourceVerified = verified,
            unresolvedConflict = m.id in conflicts)
        return EvidenceEligibility.assess(evidence, now).verdict == EvidenceEligibility.Verdict.ELIGIBLE
    }
}
