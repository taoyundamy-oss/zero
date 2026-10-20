package com.zero.v2.services.embedding

import com.zero.v2.core.data.dao.VectorIndexDao
import com.zero.v2.core.data.entity.VectorEntryEntity
import com.zero.v2.core.data.entity.VectorSourceRow
import com.zero.v2.core.data.entity.VectorVersionEntity
import com.zero.v2.services.embedding.VectorIndexWorker.Outcome
import com.zero.v2.services.retrieval.RetrievalKit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID
import kotlin.coroutines.coroutineContext

private const val BATCH_SIZE = 10

/** Resumable candidate construction; publication and read leases remain owned by VectorIndexWorker. */
internal class VectorCandidateBuilder(
    private val dao: VectorIndexDao,
    private val provider: EmbeddingProvider,
    private val locks: Map<VectorDomain, VectorIndexWorker.Locks>,
    private val publish: suspend (VectorDomain, String) -> Boolean,
) {
    private data class BuildSession(
        val domain: VectorDomain,
        val space: String,
        val active: VectorVersionEntity?,
        val total: Int,
        var candidate: VectorVersionEntity?,
    ) {
        fun checkSpace(provider: EmbeddingProvider) {
            check(provider.spaceId(domain.contract) == space) { "vector_provider_changed" }
        }
    }

    private data class Batch(
        val sources: List<VectorSourceRow>,
        val saved: Map<String, VectorEntryEntity>,
        val reusable: Map<String, VectorEntryEntity>,
        val computed: Map<String, FloatArray?>,
        val dimensions: Int,
    )

    private suspend fun loadSession(domain: VectorDomain): BuildSession {
        val space = provider.spaceId(domain.contract)
        val active = dao.active(domain.key)
        val total = dao.sourceCount(domain.key)
        val candidate = dao.versions(domain.key).firstOrNull {
            it.state in setOf("BUILDING", "VALIDATING", "READY") && it.space == space && it.baseVersion == active?.id
        }
        val session = BuildSession(domain, space, active, total, candidate)
        if (candidate != null &&
            (candidate.expectedCount != total || dao.matchingCount(candidate.id) != dao.countEntries(candidate.id))) {
            locks.getValue(domain).write.withLock { dao.abandon(candidate.id) }
            session.candidate = null
        }
        collectUnused(session)
        return session
    }

    private suspend fun collectUnused(session: BuildSession) {
        val guard = locks.getValue(session.domain)
        // A pinned reader retains its version until the entire scan completes.
        guard.readers.withLock {
            guard.write.withLock {
                val retained = setOf(session.active?.id, session.active?.baseVersion, session.candidate?.id)
                dao.versions(session.domain.key).filter { it.id !in retained }.forEach { dao.removeUnpublished(it.id) }
            }
        }
    }

    suspend fun build(domain: VectorDomain): Outcome {
        val session = loadSession(domain)
        return try {
            // A resumed READY candidate must recover from stale publication guards too.
            preflight(session) ?: buildPages(session)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: IllegalStateException) {
            abandon(session)
            Outcome.STALE
        } catch (failure: IllegalArgumentException) {
            abandon(session)
            throw failure
        }
    }

    private suspend fun preflight(session: BuildSession): Outcome? {
        val active = session.active
        val alreadyCurrent = active?.space == session.space && active.expectedCount == session.total &&
            dao.matchingCount(active.id) == session.total
        return when {
            session.total == 0 -> Outcome.EMPTY
            alreadyCurrent -> Outcome.CURRENT
            session.candidate?.state == "VALIDATING" -> Outcome.WAITING_VALIDATION
            session.candidate?.state == "READY" -> {
                publish(session.domain, checkNotNull(session.candidate).id)
                Outcome.CURRENT
            }
            else -> null
        }
    }

    private suspend fun abandon(session: BuildSession) {
        session.candidate?.let { value -> locks.getValue(session.domain).write.withLock { dao.abandon(value.id) } }
    }

    private suspend fun buildPages(session: BuildSession): Outcome {
        var after = ""
        while (true) {
            coroutineContext.ensureActive()
            session.checkSpace(provider)
            val sources = dao.sourcePage(session.domain.key, after, BATCH_SIZE)
            if (sources.isEmpty()) break
            if (!stagePage(session, sources)) return Outcome.RETRY
            after = sources.last().id
        }
        session.checkSpace(provider)
        return session.candidate?.let {
            locks.getValue(session.domain).write.withLock { dao.seal(it.id) }
            Outcome.WAITING_VALIDATION
        } ?: Outcome.EMPTY
    }

    private suspend fun prepareBatch(session: BuildSession, sources: List<VectorSourceRow>): Batch? {
        val ids = sources.map { it.id }
        val saved = session.candidate?.let { dao.entriesFor(it.id, ids).associateBy { row -> row.sourceId } }.orEmpty()
        val reusable = session.active?.takeIf { it.space == session.space }
            ?.let { dao.entriesFor(it.id, ids).associateBy { row -> row.sourceId } }.orEmpty()
        val missing = sources.filter { !matches(saved[it.id], it) && !matches(reusable[it.id], it) }
        val vectors = requestVectors(session.domain, missing) ?: return null
        session.checkSpace(provider)
        val dimensions = session.candidate?.dimensions
            ?: session.active?.takeIf { it.space == session.space }?.dimensions
            ?: vectors.firstOrNull { it != null && it.isNotEmpty() }?.size
        return dimensions?.let {
            val computed = missing.mapIndexed { index, row -> row.id to vectors.getOrNull(index) }.toMap()
            Batch(sources, saved, reusable, computed, it)
        }
    }

    private suspend fun requestVectors(domain: VectorDomain, sources: List<VectorSourceRow>): List<FloatArray?>? {
        return if (sources.isEmpty()) emptyList() else {
            try { withTimeoutOrNull(REQUEST_TIMEOUT_MS) { provider.embedBatch(sources.map(domain::input)) } }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { null }
        }
    }

    private suspend fun stagePage(session: BuildSession, sources: List<VectorSourceRow>): Boolean {
        val batch = prepareBatch(session, sources) ?: return false
        val guard = locks.getValue(session.domain)
        if (session.candidate == null) {
            val created = VectorVersionEntity(UUID.randomUUID().toString(), session.domain.key, session.space,
                batch.dimensions, session.total, session.active?.id, createdAt = System.currentTimeMillis())
            guard.write.withLock { dao.begin(created) }
            session.candidate = created
        }
        val id = checkNotNull(session.candidate).id
        val additions = batch.sources.filter { !matches(batch.saved[it.id], it) }
            .mapNotNull { row -> entryFor(id, batch, row) }
        if (additions.isNotEmpty()) guard.write.withLock { dao.stage(id, additions) }
        return sources.all { matches(batch.saved[it.id], it) || additions.any { added -> added.sourceId == it.id } }
    }

    private fun entryFor(id: String, batch: Batch, row: VectorSourceRow): VectorEntryEntity? {
        val reused = batch.reusable[row.id]?.takeIf { matches(it, row) }
        val bytes = reused?.vector ?: batch.computed[row.id]?.let { vector ->
            check(vector.size == batch.dimensions) { "vector_dimension_changed" }
            RetrievalKit.encodeEmbedding(vector)
        }
        return bytes?.let { VectorEntryEntity(id, row.id, row.text, row.title, row.heading, it) }
    }

}

private fun matches(entry: VectorEntryEntity?, source: VectorSourceRow): Boolean = entry != null &&
        entry.sourceText == source.text && entry.sourceTitle == source.title && entry.sourceHeading == source.heading
