package com.zero.v2.services.embedding

import com.zero.v2.core.data.dao.VectorIndexDao
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Explicit maintenance; shares worker locks and never changes the selected backend. */
class VectorIndexMaintenance internal constructor(
    private val dao: VectorIndexDao,
    private val provider: EmbeddingProvider,
    private val benchmark: VectorBenchmarkGate?,
    private val locks: Map<VectorDomain, VectorIndexWorker.Locks>,
) {
    suspend fun rollbackValidated(domain: VectorDomain, expectedActive: String): Boolean = withContext(Dispatchers.IO) {
        val guard = locks.getValue(domain)
        guard.build.withLock {
            guard.readers.withLock {
                guard.write.withLock {
                    check(provider.isAvailable()) { "vector_provider_unavailable" }
                    val current = checkNotNull(dao.active(domain.key))
                    check(current.id == expectedActive) { "vector_base_changed" }
                    val target = checkNotNull(current.baseVersion) { "vector_rollback_unavailable" }
                    val receipt = checkNotNull(dao.validation(target)) { "vector_rollback_receipt_missing" }
                    benchmark?.checkRankingPolicy(receipt)
                    dao.rollback(domain.key, expectedActive, target, provider.spaceId(domain.contract))
                }
            }
        }
    }
}
