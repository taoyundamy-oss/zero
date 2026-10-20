package com.zero.v2.framework.memory

import com.zero.v2.core.common.MemoryType
import com.zero.v2.core.data.entity.MemoryEntity
import org.junit.Assert.*
import org.junit.Test

class GraphExtractionBatchTest {
    private val source = MemoryEntity("user-fact:one", "我不喜欢冷饮", type = MemoryType.FACT, sourceConversationId = "chat")

    @Test fun invalidOrMissingSourceRejectsEntireResponse() {
        assertTrue(runCatching { GraphExtractionBatch.decode("{}", listOf(source), 1) }.isFailure)
        val raw = """{"entities":[],"attributes":[{"entityId":"user","key":"drink","memoryIndex":2}],"relationships":[]}"""
        assertTrue(runCatching { GraphExtractionBatch.decode(raw, listOf(source), 1) }.isFailure)
    }

    @Test fun unreferencedModelEntitiesAreNotPersistedAndQuoteIsIntact() {
        val raw = """{"entities":[{"id":"invented","name":"invented"}],"attributes":[{"entityId":"user","key":"drink","memoryIndex":1}],"relationships":[]}"""
        val result = GraphExtractionBatch.decode(raw, listOf(source), 1)
        assertEquals(listOf("user"), result.entities.map { it.id })
        assertEquals(source.content, result.attributes.single().value)
    }
}
