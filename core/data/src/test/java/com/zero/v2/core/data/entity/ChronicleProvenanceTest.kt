package com.zero.v2.core.data.entity

import org.junit.Assert.*
import org.junit.Test

class ChronicleProvenanceTest {
    private val message = MessageEntity("m", "c", "user", "只在核实后处理", source = "CHAT_WINDOW", createdAt = 10)
    @Test fun newMessagesBindAutomationMetadataWithoutChangingTheTranscript() {
        val direct = ChronicleSourceEntity.message(message, 20)!!
        val automated = ChronicleSourceEntity.message(message.copy(automationRunId = "run"), 20)!!
        assertTrue(direct.hasDirectUserProvenance())
        assertFalse(automated.hasDirectUserProvenance())
        assertNotEquals(direct.id, automated.id)
        assertEquals(message.content, direct.body)
    }
    @Test fun oldFourFieldSourceRemainsValidButDoesNotAcquireNewProof() {
        fun pack(values: List<String?>) = values.joinToString("") { v -> v?.let { "${it.length}:$it" } ?: "-1:" }
        val source = ChronicleSourceEntity.message(message, 20)!!
        val old = source.copy(provenance = pack(listOf(message.role, message.source, null, null)))
        val id = java.security.MessageDigest.getInstance("SHA-256").digest(pack(listOf(old.kind, old.anchorId,
            old.conversationId, old.parentMemoryId, old.body, old.provenance, old.recordedAt.toString()))
            .toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
        val legacy = old.copy(id = id)
        legacy.validate()
        assertFalse(legacy.hasDirectUserProvenance())
        assertNotEquals(source.id, legacy.id)
    }
    @Test fun corruptedMetadataCannotQualifyEvenWithTheOriginalId() {
        val source = ChronicleSourceEntity.message(message, 20)!!
        assertFalse(source.copy(provenance = "999999999999999:bad").hasDirectUserProvenance())
        assertFalse(source.copy(body = "替换").hasDirectUserProvenance())
    }
}
