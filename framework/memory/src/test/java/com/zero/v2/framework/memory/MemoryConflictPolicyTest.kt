package com.zero.v2.framework.memory

import com.zero.v2.core.common.MemoryType
import com.zero.v2.core.data.entity.MemoryEntity
import org.junit.Assert.*
import org.junit.Test

class MemoryConflictPolicyTest {
    private val old = MemoryEntity(id = "a", content = "我住上海。", type = MemoryType.FACT, createdAt = 1,
        origin = "USER_QUOTE", sourceMessageId = "message", sourceConversationId = "conversation")
    private val new = old.copy(id = "b", createdAt = 2)

    @Test fun preferencesAndDirectivesAreCandidatesButNeverAutomaticallyArchived() {
        for (type in listOf(MemoryType.PREFERENCE, MemoryType.DIRECTIVE)) {
            val a = new.copy(type = type); val b = old.copy(type = type)
            assertTrue(MemoryConflictPolicy.eligible(a, b))
            assertFalse(MemoryConflictPolicy.canConsolidate(a, b))
            assertFalse(MemoryConflictPolicy.eligible(a, b.copy(scope = "work")))
            assertFalse(MemoryConflictPolicy.eligible(a, old))
        }
    }
    @Test fun orderingIsStableAcrossBothDiscoveryDirectionsAndEqualTimes() {
        assertEquals(new to old, MemoryConflictPolicy.ordered(old, new))
        assertEquals(new to old, MemoryConflictPolicy.ordered(new, old))
        val tie = new.copy(createdAt = 1)
        assertEquals(tie to old, MemoryConflictPolicy.ordered(old, tie))
    }

    @Test fun onlyExactEquivalentFactsCanBeArchivedAutomatically() {
        assertTrue(MemoryConflictPolicy.canConsolidate(new, old))
        assertFalse(MemoryConflictPolicy.canConsolidate(new.copy(content = "我住北京。"), old))
        assertFalse(MemoryConflictPolicy.canConsolidate(new.copy(origin = "MODEL_NOTE"), old))
        assertFalse(MemoryConflictPolicy.canConsolidate(new.copy(sourceMessageId = "different"), old))
        assertFalse(MemoryConflictPolicy.canConsolidate(new.copy(occurredAt = 3), old))
        assertFalse(MemoryConflictPolicy.canConsolidate(new.copy(confidence = 0.1f), old))
        assertFalse(MemoryConflictPolicy.canConsolidate(new.copy(type = MemoryType.DIRECTIVE), old))
    }
}
