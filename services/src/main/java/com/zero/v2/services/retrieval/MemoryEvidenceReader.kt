package com.zero.v2.services.retrieval

import com.zero.v2.core.common.MemoryStatus
import com.zero.v2.core.data.dao.MemoryDao
import com.zero.v2.core.data.entity.MemoryConflictEntity
import com.zero.v2.core.data.entity.MemoryEntity
import com.zero.v2.core.data.entity.MemoryEvidenceSnapshot
import com.zero.v2.core.data.entity.MemoryReviewEntity
import com.zero.v2.core.data.entity.MessageEntity
import javax.inject.Inject

/** Staged, read-only adapter. No production retrieval is switched to it implicitly. */
class MemoryEvidenceReader internal constructor(
    private val read: suspend (String) -> MemoryEvidenceSnapshot?,
) {
    @Inject constructor(dao: MemoryDao) : this(dao::readEvidenceSnapshot)

    class Ticket internal constructor(
        val memoryId: String, val text: String,
        val evidenceKind: String, val scope: String,
        internal val version: String, internal val proof: Proof,
    )
    internal data class Proof(val message: MessageEntity?, val review: MemoryReviewEntity?, val parentVersion: String?)
    data class Capture(val ticket: Ticket?, val reason: String)

    suspend fun capture(memoryId: String): Capture = read(memoryId)?.let(::captureSnapshot)
        ?: Capture(null, "deleted_or_missing")

    /** Call after model output, immediately before consumption; never cache this decision. */
    suspend fun revalidate(
        ticket: Ticket, use: EvidenceSupport.Use, scope: EvidenceSupport.QueryScope,
    ): EvidenceSupport.Decision {
        val current = read(ticket.memoryId)
            ?: return EvidenceSupport.Decision(EvidenceSupport.Use.NONE, "deleted_or_missing")
        val fresh = captureSnapshot(current).ticket
        return if (fresh == null || fresh.version != ticket.version || fresh.proof != ticket.proof) {
            EvidenceSupport.Decision(EvidenceSupport.Use.NONE, "source_or_memory_changed")
        } else {
            val m = current.memory
            EvidenceSupport.assessVerified(use, true,
                EvidenceSupport.State(active = m.status == MemoryStatus.ACTIVE,
                    superseded = current.activeReplacement, validFrom = m.validFrom, validUntil = m.validUntil,
                    unresolvedConflict = current.pendingConflict), scope)
        }
    }

    internal fun captureSnapshot(snapshot: MemoryEvidenceSnapshot): Capture {
        return if (snapshot.memory.origin == "USER_CONFIRMED") captureConfirmation(snapshot) else captureQuote(snapshot)
    }

    private fun captureQuote(snapshot: MemoryEvidenceSnapshot): Capture {
        val m = snapshot.memory
        val message = snapshot.source
        val body = message?.content
        val reason = when {
            m.origin != "USER_QUOTE" -> "origin_unverified"
            message == null || !sourceMatches(message, m) -> "source_mismatch"
            !trusted(message) -> "source_not_user"
            body.isNullOrBlank() || body.length > MAX_SOURCE_CHARS -> "source_size"
            !locatable(m.content, body) -> "source_shape_unsupported"
            else -> null
        }
        return if (reason != null) Capture(null, reason) else {
            val original = checkNotNull(message)
            val text = checkNotNull(body)
            Capture(Ticket(m.id, text, "USER_QUOTE", m.scope, MemoryConflictEntity.version(m),
                Proof(original, null, null)), "captured")
        }
    }

    /** A unique whole paragraph is a location, not permission to strip its surrounding conditions. */
    private fun locatable(content: String, body: String): Boolean {
        val normalized = content.lineSequence().joinToString("\n") { it.trim() }.trim()
        val full = body.lineSequence().joinToString("\n") { it.trim() }.trim()
        return normalized.isNotBlank() && (normalized == full ||
            full.split(Regex("\n[\\t ]*\n+")).count { it.trim() == normalized } == 1)
    }

    private fun captureConfirmation(snapshot: MemoryEvidenceSnapshot): Capture {
        val m = snapshot.memory
        val review = snapshot.confirmation
        val valid = review != null && confirmationMatches(snapshot, review)
        return if (!valid) Capture(null, "confirmation_unverified") else {
            Capture(Ticket(m.id, m.content, "USER_CONFIRMATION", m.scope, MemoryConflictEntity.version(m),
                Proof(null, review, snapshot.confirmationParentVersion)), "captured_confirmation")
        }
    }

    private fun confirmationMatches(snapshot: MemoryEvidenceSnapshot, review: MemoryReviewEntity): Boolean {
        val m = snapshot.memory
        val bodyValid = m.content.isNotBlank() && m.content.length <= MAX_CONFIRMED_CHARS
        val content = review.proposedContent == m.content && review.proposedType == m.type &&
            review.proposedScope == m.scope && bodyValid && confirmedDatesMatch(review, m)
        val parent = snapshot.confirmationLinked && snapshot.confirmationParentVersion == review.baseVersion &&
            m.supersedesId == review.memoryId
        val source = m.sourceMessageId == null && m.sourceConversationId == null &&
            review.decidedAt != null && review.decidedAt == m.createdAt
        return confirmationIdentity(review, m.id) && content && parent && source
    }

    private fun confirmationIdentity(review: MemoryReviewEntity, memoryId: String): Boolean {
        val identity = review.state == "ACCEPTED" && review.evidenceKind == "USER_CONFIRMATION" &&
            review.resultMemoryId == memoryId && memoryId == "review:${review.id}"
        val times = runCatching { com.zero.v2.core.data.entity.MemoryReviewTimes.from(review) }
            .getOrElse { return false }
        return identity && review.id == com.zero.v2.core.data.entity.MemoryReviewTimes.identity(
            review.baseVersion, review.proposedContent, review.proposedType.name, review.proposedScope, times)

    }

    private fun trusted(message: MessageEntity) = message.role == "user" &&
        message.source in setOf("CHAT_WINDOW", "VOICE", "REMOTE") && message.systemSource == null &&
        message.triggerId == null && message.automationRunId == null

    private fun sourceMatches(message: MessageEntity, memory: MemoryEntity): Boolean {
        val nonEmpty = message.id.isNotBlank() && message.conversationId.isNotBlank()
        return nonEmpty && message.id == memory.sourceMessageId && message.conversationId == memory.sourceConversationId
    }

    private companion object {
        const val MAX_SOURCE_CHARS = 600
        const val MAX_CONFIRMED_CHARS = 4000
    }
}

private fun confirmedDatesMatch(review: MemoryReviewEntity, memory: MemoryEntity): Boolean =
    !review.editTimes || (review.proposedOccurredAt == memory.occurredAt &&
        review.proposedValidFrom == memory.validFrom && review.proposedValidUntil == memory.validUntil)

