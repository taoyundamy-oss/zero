package com.zero.v2.core.data.entity

import org.junit.Assert.*
import org.junit.Test

class MemoryConflictEntityTest {
    @Test fun stablePairIdentityIgnoresRetrievalCountersButTracksEdits() {
        val a = MemoryEntity("a", "原文A")
        val b = MemoryEntity("b", "原文B")
        val c = MemoryConflictEntity.candidate(a,b,"CONTRADICTION",1)
        assertEquals(c.id, MemoryConflictEntity.candidate(b,a,"OUTDATED",2).id)
        assertEquals(MemoryConflictEntity.version(a), MemoryConflictEntity.version(a.copy(accessCount = 5, lastAccessedAt = 10)))
        assertNotEquals(c.id, MemoryConflictEntity.candidate(a.copy(content = "更正"),b,"CONTRADICTION",1).id)
        assertNotEquals(c.id, MemoryConflictEntity.candidate(a.copy(revision = 2),b,"CONTRADICTION",1).id)
    }
}
