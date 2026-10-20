package com.zero.v2.services.retrieval

import com.zero.v2.core.common.MemoryStatus
import com.zero.v2.core.data.entity.MemoryEntity
import com.zero.v2.core.data.entity.MemoryEvidenceSnapshot
import com.zero.v2.core.data.entity.MessageEntity
import com.zero.v2.core.data.entity.MemoryReviewEntity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class MemoryEvidenceReaderTest {
    private val message = MessageEntity("s", "c", "user", "Only after I confirm; never automatically.",
        source = "CHAT_WINDOW", createdAt = 1)
    private val memory = MemoryEntity("m", message.content.orEmpty(), origin = "USER_QUOTE",
        sourceMessageId = "s", sourceConversationId = "c", createdAt = 1)
    private val initial = MemoryEvidenceSnapshot(memory, message, false, false)
    private var current: MemoryEvidenceSnapshot? = initial
    private val reader = MemoryEvidenceReader { current }
    private val scope = EvidenceSupport.QueryScope(100)

    @Test fun wholeSourceIsPreservedAndUnknownIsNotUpgraded() = runBlocking {
        assertEquals(message.content, reader.capture("m").ticket?.text)
        current = initial.copy(memory = memory.copy(origin = "UNKNOWN"))
        assertEquals("origin_unverified", reader.capture("m").reason)
        current = initial.copy(memory = memory.copy(content = "Only after I confirm"))
        assertEquals("source_shape_unsupported", reader.capture("m").reason)
    }

    @Test fun syntheticWrongOwnerMissingAndOversizedSourcesAreRejected() = runBlocking {
        listOf(null, message.copy(role = "assistant"), message.copy(source = null),
            message.copy(conversationId = "other"), message.copy(systemSource = "alarm"),
            message.copy(triggerId = "event"), message.copy(automationRunId = "run"),
            message.copy(content = "x".repeat(601))).forEach {
            current = initial.copy(source = it)
            assertNull(reader.capture("m").ticket)
        }
    }

    @Test fun delayedDecisionCannotSurviveDeletionOrRevision() = runBlocking {
        val ticket = checkNotNull(reader.capture("m").ticket)
        listOf(null, initial.copy(memory = memory.copy(revision = 2)),
            initial.copy(source = message.copy(content = "changed")),
            initial.copy(source = message.copy(createdAt = 2))).forEach {
            current = it
            assertEquals(EvidenceSupport.Use.NONE, reader.revalidate(ticket, EvidenceSupport.Use.CERTAIN, scope).use)
        }
    }

    @Test fun latestConflictCanDowngradeButNotCreateSupport() = runBlocking {
        val ticket = checkNotNull(reader.capture("m").ticket)
        current = initial.copy(pendingConflict = true)
        assertEquals(EvidenceSupport.Use.UNCERTAIN,
            reader.revalidate(ticket, EvidenceSupport.Use.CERTAIN, scope).use)
        assertEquals(EvidenceSupport.Use.NONE, reader.revalidate(ticket, EvidenceSupport.Use.NONE, scope).use)
    }

    @Test fun timeAndArchivalAreRecheckedWithoutDroppingHistoricalAccess() = runBlocking {
        current = initial.copy(memory = memory.copy(validUntil = 101))
        val ticket = checkNotNull(reader.capture("m").ticket)
        assertEquals(EvidenceSupport.Use.CERTAIN, reader.revalidate(ticket, EvidenceSupport.Use.CERTAIN, scope).use)
        assertEquals(EvidenceSupport.Use.NONE,
            reader.revalidate(ticket, EvidenceSupport.Use.CERTAIN, EvidenceSupport.QueryScope(101)).use)
        current = current?.copy(memory = checkNotNull(current).memory.copy(status = MemoryStatus.ARCHIVED))
        assertEquals(EvidenceSupport.Use.NONE, reader.revalidate(ticket, EvidenceSupport.Use.CERTAIN, scope).use)
        assertEquals(EvidenceSupport.Use.CERTAIN,
            reader.revalidate(ticket, EvidenceSupport.Use.CERTAIN, EvidenceSupport.QueryScope(101, true)).use)
    }

    @Test fun activeReplacementIsRecheckedAndReadsDoNotMutateRows() = runBlocking {
        val ticket = checkNotNull(reader.capture("m").ticket)
        current = initial.copy(activeReplacement = true)
        assertEquals(EvidenceSupport.Use.NONE, reader.revalidate(ticket, EvidenceSupport.Use.CERTAIN, scope).use)
        assertEquals(initial.copy(activeReplacement = true), current)
        assertNotNull(reader.capture("m").ticket)
    }

    @Test fun uniqueParagraphKeepsAllSurroundingContextAndRejectsSubstrings() = runBlocking {
        val body = "This is conditional.\n\n  Only on weekends.  \n\nNever on weekdays."
        current = initial.copy(memory = memory.copy(content = "Only on weekends."),
            source = message.copy(content = body))
        assertEquals(body, reader.capture("m").ticket?.text)
        current = current?.copy(memory = memory.copy(content = "weekends"))
        assertNull(reader.capture("m").ticket)
        current = initial.copy(memory = memory.copy(content = "Repeated."),
            source = message.copy(content = "Repeated.\n\nRepeated."))
        assertNull(reader.capture("m").ticket)
    }

    @Test fun explicitConfirmationNeedsMatchingAuditParentAndDependency() = runBlocking {
        val content = "A confirmed preference.".repeat(40)
        val base = "base-version"
        val key = listOf(base, content, memory.type.name, memory.scope).joinToString("") { "${it.length}:$it" }
        val id = EvidenceSupport.digest(key)
        val review = MemoryReviewEntity(id, "parent", base, content, memory.type, memory.scope,
            "Explicit review", null, null, "ACCEPTED", "USER_CONFIRMATION", 1, 2, "review:$id")
        val confirmed = memory.copy(id = "review:$id", content = content, origin = "USER_CONFIRMED",
            sourceMessageId = null, sourceConversationId = null, supersedesId = "parent", createdAt = 2)
        val valid = MemoryEvidenceSnapshot(confirmed, null, false, false, review, base, true)
        current = valid
        val ticket = checkNotNull(reader.capture(confirmed.id).ticket)
        assertEquals("USER_CONFIRMATION", ticket.evidenceKind)
        assertEquals(EvidenceSupport.Use.CERTAIN, reader.revalidate(ticket, EvidenceSupport.Use.CERTAIN, scope).use)
        listOf(valid.copy(confirmation = null), valid.copy(confirmationLinked = false),
            valid.copy(confirmationParentVersion = "changed"),
            valid.copy(confirmation = review.copy(state = "PENDING")),
            valid.copy(memory = confirmed.copy(content = "edited"))).forEach {
            current = it
            assertNull(reader.capture(confirmed.id).ticket)
            assertEquals(EvidenceSupport.Use.NONE, reader.revalidate(ticket, EvidenceSupport.Use.CERTAIN, scope).use)
        }
    }

    @Test fun matchingBlankSourceIdentitiesAreStillUnverified() = runBlocking {
        current = initial.copy(memory = memory.copy(sourceMessageId = "", sourceConversationId = ""),
            source = message.copy(id = "", conversationId = ""))
        assertNull(reader.capture("m").ticket)
    }
    @Test fun explicitDateConfirmationIsQualifiedAndDateTamperingRejected() = runBlocking {
        val times = com.zero.v2.core.data.entity.MemoryReviewTimes(null, 1, 10000)
        val id = com.zero.v2.core.data.entity.MemoryReviewTimes.identity("base", "确认内容", "FACT", "personal", times)
        val review = MemoryReviewEntity(id, "parent", "base", "确认内容", com.zero.v2.core.common.MemoryType.FACT,
            "personal", "人工确认", null, null, "ACCEPTED", "USER_CONFIRMATION", 1, 2, "review:$id",
            true, null, 1, 10000)
        val m = memory.copy(id = "review:$id", content = "确认内容", origin = "USER_CONFIRMED", createdAt = 2,
            type = com.zero.v2.core.common.MemoryType.FACT, scope = "personal", supersedesId = "parent",
            sourceMessageId = null, sourceConversationId = null, occurredAt = null, validFrom = 1, validUntil = 10000)
        current = MemoryEvidenceSnapshot(m, null, false, false, review, "base", true)
        assertNotNull(reader.capture(m.id).ticket)
        current = checkNotNull(current).copy(memory = m.copy(occurredAt = 5))
        assertNull(reader.capture(m.id).ticket)
    }

}
