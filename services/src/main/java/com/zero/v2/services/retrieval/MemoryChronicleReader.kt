package com.zero.v2.services.retrieval

import com.zero.v2.core.data.dao.MemoryDao
import com.zero.v2.core.data.entity.MemoryChronicleSnapshot
import javax.inject.Inject
import javax.inject.Singleton

/** Explicit read-only entry point. No model dependency and no implicit production retrieval switch. */
@Singleton
class MemoryChronicleReader internal constructor(
    private val readSnapshot: suspend (List<String>) -> MemoryChronicleSnapshot,
    private val evidence: MemoryEvidenceReader,
) {
    @Inject constructor(dao: MemoryDao, evidence: MemoryEvidenceReader) : this(dao::readChronicleSnapshot, evidence)

    /** Returned views are point-in-time, not reusable authorization for later context injection. */
    suspend fun read(ids: List<String>, scope: String, now: Long): ChronicleGraph {
        require(ids.size in 1..ChronicleGraph.MAX_NODES && ids.distinct().size == ids.size)
        require(ids.none { it.isBlank() } && scope.isNotBlank())
        val snapshot = readSnapshot(ids.sorted())
        require(snapshot.entries.all { it.memory.id in ids } && snapshot.dependencies.size <= MAX_DEPENDENCIES)
        val visible = snapshot.entries.filter { it.memory.scope == scope }
        val tickets = visible.mapNotNull { evidence.captureSnapshot(it).ticket }
        val graph = ChronicleGraph.project(
            visible, snapshot.dependencies, tickets.map { it.memoryId }.toSet(), scope, now)
        val sources = tickets.map(::source)
        require(sources.none { source -> graph.nodes.any { it.id == source.id } }) { "chronicle_identity_collision" }
        require(sources.groupBy { it.id }.values.all { it.distinct().size == 1 })
        val originEdges = tickets.map {
            ChronicleGraph.Edge(it.memoryId, source(it).id, ChronicleGraph.Relation.ORIGINATED_FROM)
        }
        return graph.copy(sources = sources.distinct().sortedBy { it.id },
            edges = graph.edges + originEdges.sortedBy { it.from },
            unavailableRequested = ids.size - visible.size)
    }

    private fun source(ticket: MemoryEvidenceReader.Ticket): ChronicleGraph.Source {
        val proof = ticket.proof
        val message = proof.message
        return if (message != null) {
            ChronicleGraph.Source("message:${message.id}", ticket.text, message.createdAt)
        } else {
            val review = checkNotNull(proof.review)
            ChronicleGraph.Source("confirmation:${review.id}", ticket.text, checkNotNull(review.decidedAt))
        }
    }

    private companion object { const val MAX_DEPENDENCIES = 1024 }
}
