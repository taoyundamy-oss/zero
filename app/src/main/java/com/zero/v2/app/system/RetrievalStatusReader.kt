package com.zero.v2.app.system

import com.zero.v2.core.data.dao.MemoryExtractionJobDao
import com.zero.v2.core.moduleapi.RetrievalDomainStatus
import com.zero.v2.services.embedding.VectorDomain
import com.zero.v2.services.embedding.VectorIndexRuntime
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/** Reads local owner metadata only. Never builds an index, evaluates a model or reads source text. */
@Singleton
class RetrievalStatusReader @Inject constructor(
    private val indexes: VectorIndexRuntime,
    private val jobs: MemoryExtractionJobDao,
) {
    data class Observation(
        val domains: List<RetrievalDomainStatus> = emptyList(),
        val extractionJobs: Int? = null,
        val extractionObservedAt: Long = 0L,
    ) {
        val observedAt: Long get() = if (domains.size == VectorDomain.entries.size && extractionJobs != null &&
            domains.all { it.readError == null }) minOf(domains.minOf { it.observedAt }, extractionObservedAt) else 0L
        val backlog: Int? get() = if (observedAt > 0L && domains.all { it.backlog != null })
            domains.sumOf { it.backlog!! } + extractionJobs!! else null
        val indexVersion: String? get() = domains.mapNotNull { value ->
            value.activeVersion?.let { "${value.domain}:$it" }
        }.takeIf { it.isNotEmpty() }?.joinToString(";")
    }

    private val lock = Mutex()
    @Volatile var observation = Observation()
        private set

    suspend fun refresh() = lock.withLock {
        val previous = observation
        val domains = VectorDomain.entries.map { domain ->
            try {
                val value = indexes.status(domain)
                RetrievalDomainStatus(domain.key, value.active, value.activeSpaceMatches, value.candidateState,
                    value.staged, value.total, value.backlog, System.currentTimeMillis())
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                previous.domains.find { it.domain == domain.key }?.copy(readError = "owner_read_failed")
                    ?: RetrievalDomainStatus(domain.key, null, null, null, null, null, null, 0L,
                        readError = "owner_read_failed")
            }
        }
        val count = try { jobs.countBacklog() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { null }
        observation = Observation(domains, count, if (count == null) 0L else System.currentTimeMillis())
    }
}
