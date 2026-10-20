package com.zero.v2.services.retrieval

import com.zero.v2.core.common.MemoryStatus
import com.zero.v2.core.data.entity.MemoryEntity
import com.zero.v2.core.data.entity.MemoryEvidenceSnapshot
import com.zero.v2.core.data.entity.MemorySourceEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChronicleGraphTest {
    private fun snap(m: MemoryEntity, conflict: Boolean = false, replaced: Boolean = false) =
        MemoryEvidenceSnapshot(m, null, conflict, replaced)
    private fun memory(id: String = "a") = MemoryEntity(id = id, content = "原文", createdAt = 100)
    private fun graph(vararg rows: MemoryEvidenceSnapshot, now: Long = 100, verified: Set<String> = setOf("a")) =
        ChronicleGraph.project(rows.toList(), emptyList(), verified, "personal", now)

    @Test fun recordedTimeDoesNotInventEventTime() {
        val node = graph(snap(memory())).nodes.single()
        assertEquals(100L, node.times.recordedAt)
        assertEquals(null, node.times.occurredAt)
    }
    @Test fun stableOrderDoesNotFollowEventTime() {
        val a = snap(memory("a").copy(occurredAt = 900))
        val b = snap(memory("b").copy(occurredAt = 1))
        assertEquals(graph(a, b), graph(b, a))
        assertEquals(listOf("a", "b"), graph(b, a).nodes.map { it.id })
    }
    @Test fun laterRecordDoesNotSupersedeEarlier() {
        val g = graph(snap(memory()), snap(memory("b").copy(createdAt = 101)), verified = setOf("a", "b"))
        assertEquals(2, g.currentCandidates.size)
        assertEquals(ChronicleGraph.Relation.RECORDED_NEXT, g.edges.single().relation)
    }
    @Test fun unknownSourceRemainsUnverified() {
        assertEquals(ChronicleGraph.Eligibility.UNVERIFIED,
            graph(snap(memory()), verified = emptySet()).nodes.single().eligibility)
    }
    @Test fun intervalStartIncludedEndExcluded() {
        val s = snap(memory().copy(validFrom = 100, validUntil = 200))
        assertEquals(ChronicleGraph.Eligibility.FUTURE, graph(s, now = 99).nodes.single().eligibility)
        assertEquals(1, graph(s, now = 100).currentCandidates.size)
        assertEquals(1, graph(s, now = 199).currentCandidates.size)
        assertEquals(ChronicleGraph.Eligibility.EXPIRED, graph(s, now = 200).nodes.single().eligibility)
    }
    @Test fun invalidIntervalRejected() {
        assertEquals(ChronicleGraph.Eligibility.INVALID_TIME,
            graph(snap(memory().copy(validFrom = 200, validUntil = 100))).nodes.single().eligibility)
    }
    @Test fun conflictAndReplacementDoNotBecomeCurrent() {
        assertTrue(graph(snap(memory(), conflict = true)).currentCandidates.isEmpty())
        assertTrue(graph(snap(memory(), replaced = true)).currentCandidates.isEmpty())
        assertTrue(graph(snap(memory().copy(status = MemoryStatus.ARCHIVED))).currentCandidates.isEmpty())
    }
    @Test fun crossScopeEdgesAreNotFollowed() {
        val a = snap(memory().copy(supersedesId = "b"))
        val b = snap(memory("b").copy(scope = "work"))
        val g = graph(a, b)
        assertEquals(listOf("a"), g.nodes.map { it.id })
        assertEquals(1, g.unresolvedLinks)
        assertTrue(g.edges.isEmpty())
    }
    @Test fun dependencyWalkIsTypedBoundedAndCycleSafe() {
        val rows = listOf(snap(memory()), snap(memory("b")), snap(memory("c")))
        val deps = listOf(MemorySourceEntity("a", "b"), MemorySourceEntity("b", "a"))
        val g = ChronicleGraph.project(rows, deps, emptySet(), "personal", 100)
        assertEquals(listOf("a", "b"), g.walk("a", ChronicleGraph.Relation.DERIVED_FROM).ids)
        assertFalse(g.walk("a", ChronicleGraph.Relation.DERIVED_FROM).truncated)
        assertTrue(g.walk("a", ChronicleGraph.Relation.DERIVED_FROM, 1).truncated)
        assertEquals(listOf("a"), g.walk("a", ChronicleGraph.Relation.SUPERSEDES).ids)
    }
    @Test fun missingDependencyExplicitAndInputUnchanged() {
        val m = memory()
        val g = ChronicleGraph.project(
            listOf(snap(m)), listOf(MemorySourceEntity("a", "missing")), emptySet(), "personal", 100)
        assertEquals(1, g.unresolvedLinks)
        assertEquals("原文", m.content)
        assertEquals(null, m.occurredAt)
    }
    @Test(expected = IllegalArgumentException::class) fun duplicateIdsRejected() {
        graph(snap(memory()), snap(memory()))
    }
    @Test(expected = IllegalArgumentException::class) fun oversizedProjectionRejected() {
        ChronicleGraph.project((0..64).map { snap(memory(it.toString())) }, emptyList(), emptySet(), "personal", 100)
    }
}
