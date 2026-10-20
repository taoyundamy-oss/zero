package com.zero.v2.framework.memory

import com.zero.v2.core.common.MemoryType
import com.zero.v2.core.data.entity.GraphMemoryEvidence
import com.zero.v2.core.data.entity.MemoryEntity
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class GraphRelationshipGroundingTest {
    @Test fun explicitRetractionProducesClosedVersion() {
        val result = GraphRelationshipGrounding.resolve(item().put("active", false), listOf(source), 2)!!
        assertEquals(2L, result.relationship.effectiveTo)
        assertNull(GraphRelationshipGrounding.resolve(item().put("active", "false"), listOf(source), 2))
    }
    private val source = MemoryEntity("user-fact:relation", "小明是我的同事。", type = MemoryType.FACT,
        sourceConversationId = "chat", createdAt = 1)
    private fun item() = JSONObject().put("from", "user").put("to", "person_ming")
        .put("type", "colleague").put("memoryIndex", 1)

    @Test fun hostBindsEvidenceFromSelectedSource() {
        val candidate = GraphRelationshipGrounding.resolve(item().put("evidence", "invented"), listOf(source), 2)!!
        assertEquals(source, candidate.source)
        assertEquals(GraphMemoryEvidence.reference(source), candidate.relationship.evidence)
    }

    @Test fun invalidOrUnsupportedSourcesAreRejected() {
        listOf(JSONObject.NULL, 0, 2, 1.5, "1").forEach {
            assertNull(GraphRelationshipGrounding.resolve(item().put("memoryIndex", it), listOf(source), 2))
        }
        listOf(source.copy(type = MemoryType.EVENT), source.copy(sourceConversationId = null),
            source.copy(content = "x".repeat(401))).forEach {
            assertNull(GraphRelationshipGrounding.resolve(item(), listOf(it), 2))
        }
        assertNull(GraphRelationshipGrounding.resolve(item().put("to", ""), listOf(source), 2))
    }
}
