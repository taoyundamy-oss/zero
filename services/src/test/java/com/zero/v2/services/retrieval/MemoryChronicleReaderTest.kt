package com.zero.v2.services.retrieval

import com.zero.v2.core.data.entity.MemoryChronicleSnapshot
import com.zero.v2.core.data.entity.MemoryEntity
import com.zero.v2.core.data.entity.MemoryEvidenceSnapshot
import com.zero.v2.core.data.entity.MessageEntity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryChronicleReaderTest {
    private val message = MessageEntity("source", "conversation", "user", "只有确认后才开放。",
        createdAt = 10, source = "CHAT_WINDOW")
    private val memory = MemoryEntity("m", message.content.orEmpty(), origin = "USER_QUOTE",
        sourceMessageId = message.id, sourceConversationId = message.conversationId, createdAt = 20)
    private var rows = listOf(MemoryEvidenceSnapshot(memory, message, false, false))
    private var reads = 0
    private val reader = MemoryChronicleReader({
        reads++
        MemoryChronicleSnapshot(rows, emptyList())
    }, MemoryEvidenceReader { error("separate_nontransactional_read") })

    @Test fun sourceAndDerivedTimesRemainSeparate() = runBlocking {
        val result = reader.read(listOf("m"), "personal", 100)
        assertEquals(10L, result.sources.single().recordedAt)
        assertEquals(20L, result.nodes.single().times.recordedAt)
        assertEquals(null, result.nodes.single().times.occurredAt)
        assertEquals(message.content, result.sources.single().text)
        assertEquals(1, result.currentCandidates.size)
        assertEquals(1, reads)
    }
    @Test fun unknownMemoryVisibleWithoutCertifiedSource() = runBlocking {
        rows = rows.map { it.copy(memory = memory.copy(origin = "UNKNOWN")) }
        val result = reader.read(listOf("m"), "personal", 100)
        assertEquals(1, result.nodes.size)
        assertTrue(result.sources.isEmpty())
        assertTrue(result.currentCandidates.isEmpty())
    }
    @Test fun missingSourceDoesNotHideHistoricalRecordOrCertifyIt() = runBlocking {
        rows = rows.map { it.copy(source = null) }
        val result = reader.read(listOf("m"), "personal", 100)
        assertEquals(1, result.nodes.size)
        assertTrue(result.currentCandidates.isEmpty())
    }
    @Test fun deletionDisappearsOnNextReadWithoutCaching() = runBlocking {
        assertEquals(1, reader.read(listOf("m"), "personal", 100).nodes.size)
        rows = emptyList()
        val next = reader.read(listOf("m"), "personal", 100)
        assertTrue(next.nodes.isEmpty())
        assertTrue(next.sources.isEmpty())
        assertEquals(1, next.unavailableRequested)
    }
    @Test fun sourceChangeRevokesQualification() = runBlocking {
        reader.read(listOf("m"), "personal", 100)
        rows = rows.map { it.copy(source = message.copy(content = "已修改")) }
        assertTrue(reader.read(listOf("m"), "personal", 100).currentCandidates.isEmpty())
    }
    @Test fun scopeDoesNotExposeOtherSourceText() = runBlocking {
        val result = reader.read(listOf("m"), "work", 100)
        assertTrue(result.nodes.isEmpty())
        assertTrue(result.sources.isEmpty())
    }
    @Test fun provenanceEdgeDoesNotBecomeTemporalOrCausalSupport() = runBlocking {
        val result = reader.read(listOf("m"), "personal", 100)
        assertEquals(listOf("m", "message:source"), result.walk("m", ChronicleGraph.Relation.ORIGINATED_FROM).ids)
        assertEquals(listOf("m"), result.walk("m", ChronicleGraph.Relation.SUPERSEDES).ids)
    }
    @Test fun fullSourceConditionsPreservedForParagraphMemory() = runBlocking {
        val body = "前提不能省略。\n\n${memory.content}"
        rows = rows.map { it.copy(source = message.copy(content = body)) }
        assertEquals(body, reader.read(listOf("m"), "personal", 100).sources.single().text)
    }
    @Test fun sourceIdentityCollisionRejectedWithoutRewritingHistory() = runBlocking {
        rows = rows.map { it.copy(memory = memory.copy(id = "message:source")) }
        try {
            reader.read(listOf("message:source"), "personal", 100)
            error("expected_rejection")
        } catch (error: IllegalArgumentException) {
            assertEquals("chronicle_identity_collision", error.message)
        }
        assertEquals("message:source", rows.single().memory.id)
    }
    @Test fun invalidInputRejectedBeforeRead() = runBlocking {
        for (ids in listOf(emptyList(), listOf("m", "m"), listOf(""))) {
            try {
                reader.read(ids, "personal", 100)
                error("expected_rejection")
            } catch (_: IllegalArgumentException) { }
        }
        assertEquals(0, reads)
    }
}
