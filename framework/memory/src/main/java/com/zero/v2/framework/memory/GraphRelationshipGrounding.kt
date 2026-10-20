package com.zero.v2.framework.memory

import com.zero.v2.core.data.entity.GraphMemoryEvidence
import com.zero.v2.core.data.entity.MemoryEntity
import com.zero.v2.core.data.entity.UserGraphRelationship
import org.json.JSONObject

/** Source identity belongs to the host; relation classification remains a model inference. */
internal object GraphRelationshipGrounding {
    data class Candidate(val relationship: UserGraphRelationship, val source: MemoryEntity)

    fun resolve(item: JSONObject, memories: List<MemoryEntity>, now: Long): Candidate? {
        val index = item.opt("memoryIndex") as? Int ?: return null
        val source = memories.getOrNull(index - 1) ?: return null
        if (!GraphMemoryEvidence.eligible(source) || source.content.isBlank() || source.content.length > 400) return null
        val from = item.optString("from").trim()
        val to = item.optString("to").trim()
        val type = item.optString("type").trim()
        if (from.isBlank() || to.isBlank() || type.isBlank()) return null
        val active = if (item.has("active")) item.opt("active") as? Boolean ?: return null else true
        return Candidate(UserGraphRelationship(from, to, type, now,
            effectiveTo = if (active) null else now,
            evidence = GraphMemoryEvidence.reference(source)), source)
    }
}
