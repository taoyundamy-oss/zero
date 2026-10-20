package com.zero.v2.framework.memory

import com.zero.v2.core.data.entity.AttributeSource
import com.zero.v2.core.data.entity.GraphMemoryEvidence
import com.zero.v2.core.data.entity.MemoryEntity
import com.zero.v2.core.data.entity.UserGraphAttribute
import org.json.JSONObject

/** The model assigns a category; the host supplies the complete quote and source identity. */
internal object GraphAttributeGrounding {
    data class Candidate(val attribute: UserGraphAttribute, val source: MemoryEntity)

    fun resolve(item: JSONObject, memories: List<MemoryEntity>, now: Long): Candidate? {
        val index = item.opt("memoryIndex") as? Int ?: return null
        val memory = memories.getOrNull(index - 1) ?: return null
        if (!GraphMemoryEvidence.eligible(memory) || memory.content.isBlank() || memory.content.length > 400) return null
        val entity = item.optString("entityId").trim()
        val key = item.optString("key").trim()
        if (entity.isBlank() || key.isBlank()) return null
        return Candidate(UserGraphAttribute(
            entityId = entity, key = key, value = memory.content,
            source = AttributeSource.EXTRACTED, confidence = minOf(0.4f, memory.confidence),
            effectiveFrom = now, evidence = GraphMemoryEvidence.reference(memory),
        ), memory)
    }
}
