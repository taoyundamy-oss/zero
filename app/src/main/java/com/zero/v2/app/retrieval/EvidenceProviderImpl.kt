package com.zero.v2.app.retrieval

import com.zero.v2.app.knowledge.KnowledgeModule
import com.zero.v2.core.common.EvidenceRef
import com.zero.v2.framework.memory.MemoryModule
import com.zero.v2.services.retrieval.EvidencePolicy
import com.zero.v2.services.retrieval.EvidenceProvider
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import com.zero.v2.services.embedding.VectorIndexRuntime
import com.zero.v2.services.embedding.VectorDomain
import com.zero.v2.services.retrieval.BoundedSemanticReranker
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 证据提供方实现 — R3（设计§R3）：回合入口并行询问 Memory/Knowledge，跨域 RRF 融合 → top-K 短证据带来源。
 *
 * Local lexical fallback plus shared published-vector retrieval and one bounded relevance selection.
 * Query vectors and exact selection responses are reused; failures retain the local fallback.
 *
 * 无相关 → 空（不注入，不伪造）；任一域异常 → 该域空、另一域照常（永不哑火）。
 * 只读短引用，不改变 Memory/KB 写入域所有权（与 FC 并存）。
 */
@Singleton
class EvidenceProviderImpl @Inject constructor(
    private val memoryModule: MemoryModule,
    private val knowledgeModule: KnowledgeModule,
    private val vectorIndex: VectorIndexRuntime? = null,
    private val reranker: BoundedSemanticReranker? = null,
    private val lifecycleGuard: dagger.Lazy<com.zero.v2.core.modulemanager.ModuleLifecycleManager>? = null,
) : EvidenceProvider {
    override suspend fun revalidate(refs: List<EvidenceRef>): List<EvidenceRef> = refs.filter { ref ->
        if (ref.domain != com.zero.v2.core.common.EvidenceDomain.MEMORY) knowledgeModule.isEnabled else
            domainEvidence(memoryModule) {
                if (memoryModule.revalidateSourceEvidence(ref)) listOf(ref) else emptyList()
            }.isNotEmpty()
    }
    override suspend fun contextMemories(knownIds: List<String>): List<com.zero.v2.services.retrieval.ContextMemory> {
        if (!memoryModule.isEnabled) return emptyList()
        val read: suspend () -> List<com.zero.v2.services.retrieval.ContextMemory> = { memoryModule.contextMemories(knownIds) }
        return lifecycleGuard?.get()?.safeCall(memoryModule, "context_memories", read).orEmpty()
    }

    override suspend fun evidenceFor(query: String, limit: Int): List<EvidenceRef> = coroutineScope {
        if (query.isBlank() || limit <= 0) return@coroutineScope emptyList()
        val cappedLimit = limit.coerceAtMost(EvidencePolicy.MAX_REFS)
        val candidateLimit = if (vectorIndex != null && reranker != null) CANDIDATES_PER_DOMAIN else cappedLimit * 2
        // 并行询问两域（设计§R3"回合入口并行询问"）；异常隔离——一域失败不影响另一域
        val memDef = async { domainEvidence(memoryModule) { memoryModule.evidenceCandidates(query, candidateLimit) } }
        val kbDef = async { domainEvidence(knowledgeModule) { knowledgeModule.evidenceCandidates(query, candidateLimit) } }
        val mem = memDef.await()
        val kb = kbDef.await()
        val fallback = EvidencePolicy.selectTopK(if (memoryModule.isEnabled) mem else emptyList(),
            if (knowledgeModule.isEnabled) kb else emptyList(), k = cappedLimit)
        if (vectorIndex == null || reranker == null) return@coroutineScope fallback
        val selected = try {
            withTimeoutOrNull(SEMANTIC_DEADLINE_MS) { semanticEvidence(query, mem, kb, cappedLimit) }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { null }
        selected ?: fallback
    }

    private suspend fun semanticEvidence(
        query: String, memory: List<EvidenceRef>, knowledge: List<EvidenceRef>, limit: Int,
    ): List<EvidenceRef>? {
        val domains = buildSet {
            if (memoryModule.isEnabled) add(VectorDomain.MEMORY)
            if (knowledgeModule.isEnabled && knowledgeModule.config.hybridEmbedding) add(VectorDomain.KNOWLEDGE)
        }
        val entries = checkNotNull(vectorIndex).searchDomains(query, CANDIDATES_PER_DOMAIN, domains)
        val semanticMemory = domainEvidence(memoryModule) {
            memoryModule.semanticEvidenceCandidates(query, entries[VectorDomain.MEMORY].orEmpty())
        }
        val semanticKnowledge = domainEvidence(knowledgeModule) {
            knowledgeModule.semanticEvidenceCandidates(query, entries[VectorDomain.KNOWLEDGE].orEmpty())
        }
        val fusedMemory = com.zero.v2.services.retrieval.RetrievalKit.rrfFuse(semanticMemory, memory,
            limit = CANDIDATES_PER_DOMAIN, keyOf = { it.key() }).map { it.first }
        val fusedKnowledge = com.zero.v2.services.retrieval.RetrievalKit.rrfFuse(semanticKnowledge, knowledge,
            limit = CANDIDATES_PER_DOMAIN, keyOf = { it.key() }).map { it.first }
        val candidates = com.zero.v2.services.retrieval.RetrievalKit.rrfFuseEvidence(fusedMemory, fusedKnowledge,
            limit = CANDIDATES_PER_DOMAIN * 2).map { it.first }
            .distinctBy { if (it.completeSource) "${it.key()}:${it.sourceVersion}" else it.bodyDigest ?: it.key() }
            .take(BoundedSemanticReranker.MAX_CANDIDATES)
        if (candidates.isEmpty()) return emptyList()
        val byId = candidates.associateBy { "${it.key()}:${it.bodyDigest.orEmpty()}" }
        val result = checkNotNull(reranker).selectEvidence(query,
            byId.map { (id, ref) -> BoundedSemanticReranker.Candidate(id, ref.content) })
        if (!result.completed) return null
        return result.ids.mapNotNull(byId::get).filter {
            if (it.domain == com.zero.v2.core.common.EvidenceDomain.MEMORY) memoryModule.isEnabled else knowledgeModule.isEnabled
        }.take(limit)
    }

    private companion object {
        // Combined published-index scan plus one model selection; the model itself remains bounded to 3s.
        const val SEMANTIC_DEADLINE_MS = 5000L
        const val CANDIDATES_PER_DOMAIN = 8
    }

    private suspend fun domainEvidence(
        module: com.zero.v2.core.moduleapi.IZeroModule, read: suspend () -> List<EvidenceRef>,
    ): List<EvidenceRef> {
        if (!module.isEnabled) return emptyList()
        return try {
            lifecycleGuard?.get()?.let { it.safeCall(module, "evidence_candidates", read).orEmpty() } ?: read()
        }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) { emptyList() }
    }
}

