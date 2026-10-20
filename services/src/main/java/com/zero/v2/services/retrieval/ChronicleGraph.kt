package com.zero.v2.services.retrieval

import com.zero.v2.core.common.MemoryStatus
import com.zero.v2.core.common.MemoryType
import com.zero.v2.core.data.entity.MemoryEvidenceSnapshot
import com.zero.v2.core.data.entity.MemorySourceEntity

/** Read-only local projection. Eligibility never attests semantic support or authorizes injection. */
data class ChronicleGraph(
    val nodes: List<Node>, val edges: List<Edge>, val unresolvedLinks: Int,
    val sources: List<Source> = emptyList(),
    val unavailableRequested: Int = 0,
) {
    enum class Relation { RECORDED_NEXT, DERIVED_FROM, SUPERSEDES, ORIGINATED_FROM }
    enum class Eligibility { CANDIDATE, UNVERIFIED, HISTORICAL, SUPERSEDED, FUTURE, EXPIRED, CONFLICT, INVALID_TIME }
    data class Source(val id: String, val text: String, val recordedAt: Long)
    data class Times(val recordedAt: Long, val occurredAt: Long?, val validFrom: Long?, val validUntil: Long?)
    data class Node(
        val id: String, val content: String, val type: MemoryType, val revision: Int,
        val times: Times, val eligibility: Eligibility,
    )
    data class Edge(val from: String, val to: String, val relation: Relation)
    data class Walk(val ids: List<String>, val truncated: Boolean)

    val currentCandidates: List<Node> get() = nodes.filter { it.eligibility == Eligibility.CANDIDATE }

    /** Follow one explicit relation only; bounded and cycle-safe, never interprets ordering as causality. */
    fun walk(start: String, relation: Relation, limit: Int = MAX_NODES): Walk {
        require(limit in 1..MAX_NODES)
        val known = nodes.any { it.id == start } || sources.any { it.id == start }
        val seen = linkedSetOf<String>().apply { if (known) add(start) }
        val queue = ArrayDeque<String>().apply { addAll(seen) }
        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            for (edge in edges.filter { it.from == current && it.relation == relation }) {
                if (edge.to in seen) continue
                if (seen.size == limit) return Walk(seen.toList(), true)
                seen.add(edge.to)
                queue.add(edge.to)
            }
        }
        return Walk(seen.toList(), false)
    }

    companion object {
        const val MAX_NODES = 64

        internal fun project(
            snapshots: List<MemoryEvidenceSnapshot>, dependencies: List<MemorySourceEntity>,
            verifiedIds: Set<String>, scope: String, now: Long,
        ): ChronicleGraph {
            require(scope.isNotBlank() && snapshots.size <= MAX_NODES)
            require(snapshots.map { it.memory.id }.distinct().size == snapshots.size)
            val visible = snapshots.filter { it.memory.scope == scope }
                .sortedWith(compareBy({ it.memory.createdAt }, { it.memory.id }))
            val ids = visible.map { it.memory.id }.toSet()
            val nodes = visible.map { snapshot ->
                val m = snapshot.memory
                Node(m.id, m.content, m.type, m.revision, Times(m.createdAt, m.occurredAt, m.validFrom, m.validUntil),
                    eligibility(snapshot, m.id in verifiedIds, now))
            }
            val links = dependencies.filter { it.summaryId in ids }
                .map { Edge(it.summaryId, it.sourceId, Relation.DERIVED_FROM) } +
                visible.mapNotNull { s -> s.memory.supersedesId?.let { Edge(s.memory.id, it, Relation.SUPERSEDES) } }
            val resolved = links.filter { it.to in ids }.distinct()
            val order = nodes.zipWithNext { a, b -> Edge(a.id, b.id, Relation.RECORDED_NEXT) }
            val edges = (resolved + order).sortedWith(compareBy({ it.relation.name }, { it.from }, { it.to }))
            return ChronicleGraph(nodes, edges,
                links.filter { it.to !in ids }.distinct().size)
        }

        private fun eligibility(s: MemoryEvidenceSnapshot, verified: Boolean, now: Long): Eligibility {
            val m = s.memory
            val from = m.validFrom
            val until = m.validUntil
            return when {
                from != null && until != null && from >= until -> Eligibility.INVALID_TIME
                m.status != MemoryStatus.ACTIVE -> Eligibility.HISTORICAL
                s.activeReplacement -> Eligibility.SUPERSEDED
                from != null && now < from -> Eligibility.FUTURE
                until != null && now >= until -> Eligibility.EXPIRED
                s.pendingConflict -> Eligibility.CONFLICT
                !verified -> Eligibility.UNVERIFIED
                else -> Eligibility.CANDIDATE
            }
        }
    }
}
