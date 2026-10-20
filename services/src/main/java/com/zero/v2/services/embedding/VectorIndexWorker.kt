package com.zero.v2.services.embedding

import com.zero.v2.core.data.dao.VectorIndexDao
import com.zero.v2.core.data.entity.VectorEntryEntity
import com.zero.v2.core.data.entity.VectorSourceRow
import com.zero.v2.core.data.entity.VectorValidationEntity
import com.zero.v2.services.retrieval.PagedSemanticSearch
import com.zero.v2.services.retrieval.RetrievalKit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton

private const val INPUT_CHAR_LIMIT = 1000
internal const val REQUEST_TIMEOUT_MS = 60_000L
private const val RETRY_INTERVAL_MS = 30 * 60_000L
private const val MAX_RESULTS = 100

enum class VectorDomain(val key: String, val contract: String) {
    MEMORY("memory", EmbeddingSpace.MEMORY),
    KNOWLEDGE("knowledge", EmbeddingSpace.KNOWLEDGE);

    fun input(source: VectorSourceRow): String = when (this) {
        MEMORY -> source.text.take(INPUT_CHAR_LIMIT)
        KNOWLEDGE -> (("《${source.title}》" + if (source.heading.isNotBlank()) " ${source.heading}" else "") +
            " ▸ ${source.text}").take(INPUT_CHAR_LIMIT)
    }
}

interface VectorIndexRuntime {
    val queryBackendIdentity: String get() = ""
    fun retryAt(domain: VectorDomain): Long = Long.MAX_VALUE
    suspend fun evaluationReport(domain: VectorDomain): String? = null
    suspend fun build(domain: VectorDomain): VectorIndexWorker.Outcome
    suspend fun status(domain: VectorDomain): VectorIndexWorker.Status
    suspend fun search(domain: VectorDomain, query: String, limit: Int,
        knowledgeDocuments: List<String>? = null): List<VectorEntryEntity>?
    suspend fun similar(domain: VectorDomain, sourceId: String, threshold: Float, limit: Int): List<VectorEntryEntity>?
    suspend fun searchDomains(
        query: String, limit: Int, domains: Set<VectorDomain>,
    ): Map<VectorDomain, List<VectorEntryEntity>> {
        val backend = queryBackendIdentity
        val found = coroutineScope {
            domains.map { domain -> async { domain to search(domain, query, limit) } }
                .map { it.await() }.mapNotNull { (domain, rows) -> rows?.let { domain to it } }.toMap()
        }
        return if (queryBackendIdentity == backend) found else emptyMap()
    }
}

/** One builder per domain; network work never holds a Room transaction or blocks version readers. */
@Singleton
class VectorIndexWorker @Inject constructor(
    private val dao: VectorIndexDao,
    private val provider: EmbeddingProvider,
    private val journal: VectorEvaluationJournal = VectorEvaluationJournal(null as java.io.File?),
    private val benchmark: VectorBenchmarkGate? = null,
) : VectorIndexRuntime {
    override val queryBackendIdentity: String get() = provider.identity
    data class Status(
        val active: String?, val candidateState: String?, val staged: Int, val total: Int, val backlog: Int = total,
        val activeSpaceMatches: Boolean? = null, val rollbackTarget: String? = null,
    )
    enum class Outcome { CURRENT, EMPTY, WAITING_VALIDATION, RETRY, STALE, NO_PROVIDER }
    data class Evaluation(val published: Boolean, val evidence: VectorValidationEntity)
    internal class Locks {
        val build = Mutex()
        val write = Mutex()
        val readers = Mutex()
        @Volatile var retryAt = Long.MAX_VALUE
    }
    private val locks = VectorDomain.entries.associateWith { Locks() }
    val maintenance = VectorIndexMaintenance(dao, provider, benchmark, locks)
    private val queryVectors = QueryEmbeddingCache(provider)
    private val builder = VectorCandidateBuilder(dao, provider, locks, ::publishValidated)

    override suspend fun status(domain: VectorDomain): Status = withContext(Dispatchers.IO) {
        val space = provider.spaceId(domain.contract)
        val active = dao.active(domain.key)
        val pending = dao.versions(domain.key).firstOrNull { it.state in setOf("BUILDING", "VALIDATING", "READY") }
        val total = dao.sourceCount(domain.key)
        val published = if (active?.space == space) dao.matchingCount(active.id) else 0
        check(provider.spaceId(domain.contract) == space) { "vector_provider_changed" }
        Status(active?.id, pending?.state, pending?.let { dao.countEntries(it.id) } ?: 0,
            total, (total - published).coerceAtLeast(0), active?.let { it.space == space }, active?.baseVersion)
    }

    override fun retryAt(domain: VectorDomain): Long = locks.getValue(domain).retryAt

    override suspend fun evaluationReport(domain: VectorDomain): String? = withContext(Dispatchers.IO) {
        journal.latest(domain)?.let { raw ->
            val report = org.json.JSONObject(raw)
            report.put("currentlyActive", dao.active(domain.key)?.id == report.getString("versionId"))
            report.put("currentRankingPolicyMatches", report.getJSONObject("metrics")
                .optString("rankingPolicy", "vector-only-v1") == (benchmark?.rankingPolicy ?: "vector-only-v1"))
            report.toString()
        }
    }

    override suspend fun build(domain: VectorDomain): Outcome = withContext(Dispatchers.IO) {
        val guard = locks.getValue(domain)
        guard.build.withLock {
            guard.retryAt = Long.MAX_VALUE
            var finished = false
            try {
                if (!provider.isAvailable()) {
                    finished = true
                    return@withLock Outcome.NO_PROVIDER
                }
                var outcome = builder.build(domain)
                if (outcome == Outcome.STALE) outcome = builder.build(domain)
                if (outcome == Outcome.RETRY && !provider.isAvailable()) outcome = Outcome.NO_PROVIDER
                if (outcome == Outcome.WAITING_VALIDATION && benchmark != null) outcome = validateBenchmark(domain)
                if (outcome == Outcome.RETRY || outcome == Outcome.STALE) {
                    guard.retryAt = System.currentTimeMillis() + RETRY_INTERVAL_MS
                }
                finished = true
                outcome
            } catch (_: EmbeddingAuthorizationException) {
                finished = true
                Outcome.NO_PROVIDER
            } catch (cancelled: CancellationException) {
                finished = true
                throw cancelled
            } finally {
                if (!finished) guard.retryAt = System.currentTimeMillis() + RETRY_INTERVAL_MS
            }
        }
    }

    private suspend fun validateBenchmark(domain: VectorDomain): Outcome {
        val candidate = dao.versions(domain.key).first { it.state == "VALIDATING" }
        val gate = checkNotNull(benchmark)
        if (journal.rejected(domain, candidate.id, candidate.digest, VectorRecallBenchmark.dataset(domain).digest(),
                gate.rankingPolicy)) {
            return Outcome.WAITING_VALIDATION
        }
        val baseline = candidate.baseVersion?.let { checkNotNull(dao.validation(it)) }
        val result = gate.evaluate(candidate, baseline, domain)
        journal.record(domain, result.evidence, published = false)
        return if (result.accepted) {
            locks.getValue(domain).write.withLock {
                check(candidate.space == provider.spaceId(domain.contract)) { "vector_provider_changed" }
                gate.checkRankingPolicy(result.evidence)
                dao.approve(result.evidence)
            }
            val published = publishValidated(domain, candidate.id)
            journal.record(domain, result.evidence, published)
            Outcome.CURRENT
        } else {
            if (org.json.JSONObject(result.evidence.report).optBoolean("retryable")) Outcome.RETRY
            else Outcome.WAITING_VALIDATION
        }
    }

    suspend fun publishValidated(domain: VectorDomain, id: String): Boolean = withContext(Dispatchers.IO) {
        locks.getValue(domain).write.withLock {
            val version = checkNotNull(dao.version(id))
            check(version.domain == domain.key && version.space == provider.spaceId(domain.contract)) {
                "vector_provider_changed"
            }
            dao.validation(id)?.let { benchmark?.checkRankingPolicy(it) }
            dao.publish(id)
        }
    }


    /** Explicit labelled acceptance only: constructing a candidate never invents its ground truth. */
    suspend fun evaluateAndPublish(id: String, dataset: VectorRecallDataset): Evaluation = withContext(Dispatchers.IO) {
        val data = dataset.snapshot()
        val guard = locks.getValue(data.domain)
        guard.build.withLock {
            val result = VectorRecallEvaluator(dao, provider).evaluate(id, data)
            journal.record(data.domain, result.evidence, published = false)
            if (!result.accepted) return@withLock Evaluation(false, result.evidence)
            guard.write.withLock {
                check(dao.version(id)?.space == provider.spaceId(data.domain.contract)) { "vector_provider_changed" }
                dao.approve(result.evidence)
            }
            val published = publishValidated(data.domain, id)
            journal.record(data.domain, result.evidence, published)
            Evaluation(published, result.evidence)
        }
    }

    /** null means no published version yet: the caller may read its unchanged legacy columns. */
    override suspend fun search(
        domain: VectorDomain, query: String, limit: Int, knowledgeDocuments: List<String>?,
    ): List<VectorEntryEntity>? = withContext(Dispatchers.IO) {
        require(knowledgeDocuments == null || domain == VectorDomain.KNOWLEDGE)
        val documents = knowledgeDocuments?.distinct()
        locks.getValue(domain).readers.withLock {
            val version = dao.active(domain.key) ?: return@withLock null
            val space = provider.spaceId(domain.contract)
            if (version.space != space || limit <= 0 || documents?.isEmpty() == true) return@withLock emptyList()
            val vector = try { withTimeoutOrNull(REQUEST_TIMEOUT_MS) { queryVectors.get(query) } }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { null } ?: return@withLock emptyList()
            val validVector = vector.size == version.dimensions &&
                vector.all { it.isFinite() } && vector.any { it != 0f }
            if (!validVector || provider.spaceId(domain.contract) != space) {
                return@withLock emptyList()
            }
            val result = rank(version.id, vector, limit.coerceAtMost(MAX_RESULTS), documents)
            if (provider.spaceId(domain.contract) != space) emptyList() else result
        }
    }

    override suspend fun similar(
        domain: VectorDomain, sourceId: String, threshold: Float, limit: Int,
    ): List<VectorEntryEntity>? = withContext(Dispatchers.IO) {
        locks.getValue(domain).readers.withLock {
            val version = dao.active(domain.key) ?: return@withLock null
            val space = provider.spaceId(domain.contract)
            if (version.space != space || limit <= 0) return@withLock emptyList()
            val target = dao.publishedEntry(version.id, sourceId) ?: return@withLock emptyList()
            val vector = RetrievalKit.decodeEmbedding(target.vector)
            val result = rank(version.id, vector, limit.coerceAtMost(MAX_RESULTS) + 1).filter {
                it.sourceId != sourceId &&
                    RetrievalKit.cosineSimilarity(vector, RetrievalKit.decodeEmbedding(it.vector)) >= threshold
            }.take(limit)
            if (provider.spaceId(domain.contract) != space) emptyList() else result
        }
    }

    private suspend fun rank(
        id: String, vector: FloatArray, limit: Int, knowledgeDocuments: List<String>? = null,
    ): List<VectorEntryEntity> {
        val scorer = com.zero.v2.services.retrieval.EncodedCosineScorer(vector)
        return PagedSemanticSearch.search(
        limit.coerceAtMost(MAX_RESULTS + 1), { after, size ->
            if (knowledgeDocuments == null) dao.publishedPage(id, after, size)
            else com.zero.v2.core.data.knowledge.ScopedDocumentQuery.page(knowledgeDocuments, size, { it.sourceId }) {
                dao.scopedPublishedPage(id, after, it, size)
            }
        }, { it.sourceId },
        { row -> scorer.score(row.vector) })
    }


}
