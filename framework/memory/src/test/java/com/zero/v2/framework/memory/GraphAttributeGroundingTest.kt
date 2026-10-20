package com.zero.v2.framework.memory

import com.zero.v2.core.common.MemoryType
import com.zero.v2.core.data.entity.AttributeSource
import com.zero.v2.core.data.entity.GraphMemoryEvidence
import com.zero.v2.core.data.entity.MemoryEntity
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class GraphAttributeGroundingTest {
    private val memory = MemoryEntity("user-fact:source", "更正：我不喜欢冷饮，只喝热水。", type = MemoryType.FACT,
        sourceConversationId = "chat", createdAt = 1)
    private fun item() = JSONObject().put("entityId", "user").put("key", "preference.drink").put("memoryIndex", 1)

    @Test fun modelCannotRewriteNegationOrCertifyItsOwnConfidence() {
        val response = item().put("value", "喜欢冷饮").put("source", "USER_STATED").put("confidence", 1)
        val result = GraphAttributeGrounding.resolve(response, listOf(memory), 2)!!
        assertEquals(memory.content, result.attribute.value)
        assertEquals(AttributeSource.EXTRACTED, result.attribute.source)
        assertEquals(0.4f, result.attribute.confidence)
        assertEquals(GraphMemoryEvidence.reference(memory), result.attribute.evidence)
    }

    @Test fun absentOutOfRangeOrFractionalSourceIsRejected() {
        listOf(JSONObject.NULL, 0, -1, 2, 1.5, "1").forEach { index ->
            assertNull(GraphAttributeGrounding.resolve(item().put("memoryIndex", index), listOf(memory), 2))
        }
    }

    @Test fun unsourcedNotesAndOversizeInputsAreRejected() {
        listOf(memory.copy(sourceConversationId = null), memory.copy(type = MemoryType.EVENT),
            memory.copy(id = "legacy"), memory.copy(content = "x".repeat(401))).forEach { source ->
            assertNull(GraphAttributeGrounding.resolve(item(), listOf(source), 2))
        }
    }
}
