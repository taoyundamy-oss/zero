package com.zero.v2.services.retrieval

import com.zero.v2.core.common.EvidenceDomain
import com.zero.v2.core.common.EvidenceRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R2 验收：100/1k/10k 样本上的有界候选预筛 + 余弦重排 + 跨域 RRF + 词法降级 + 重建恢复。
 *
 * JVM 纯逻辑模拟（无 Room 运行时）：用内存列表模拟 DAO 全表 vs 有界查询，验证——
 * 1. 峰值工作集受 [RetrievalKit.DEFAULT_CANDIDATE_CAP] 约束、不随全表规模膨胀（替代旧全表余弦 L-7 债）；
 * 2. 召回权衡：相关项在有界 cap 内 → 向量路命中；超出 cap → 向量路漏、词法降级兜底；
 * 3. 索引失效/重建可恢复：向量空 → 词法降级；重嵌后 → 向量路恢复。
 *
 * 真实 Room LIMIT 查询的 P50/P95 延迟与 10k 真库召回 = 设备/集成项（L3，本轮无在线设备）。
 */
class BoundedRetrievalBenchmarkTest {

    private data class FakeMem(
        val id: String,
        val content: String,
        val importance: Float,
        val embedModel: String,   // "" = 无向量/未知模型
        val embedding: FloatArray? = null,
    )

    /** 模拟生产 [MemoryDao.getActiveWithEmbeddingBounded]：按模型预筛 + 重要性 DESC LIMIT cap。 */
    private fun boundedVectorCandidates(corpus: List<FakeMem>, model: String, cap: Int): List<FakeMem> =
        corpus.filter { it.embedding != null && it.embedModel == model }
            .sortedByDescending { it.importance }
            .take(cap)

    /** 模拟生产 embeddingCandidates 余弦重排（Kotlin 层 cosine）。 */
    private fun cosineRerank(candidates: List<FakeMem>, queryVec: FloatArray): List<Pair<FakeMem, Float>> =
        candidates.mapNotNull { c ->
            val v = c.embedding ?: return@mapNotNull null
            if (v.size != queryVec.size) null
            else c to RetrievalKit.cosineSimilarity(queryVec, v)
        }.sortedByDescending { it.second }

    /** 模拟词法降级链（LIKE 子串；中文无分词假设）。 */
    private fun lexicalFallback(corpus: List<FakeMem>, query: String): List<FakeMem> =
        corpus.filter { it.content.contains(query) }

    private fun toRef(m: FakeMem, score: Float = 0f): EvidenceRef =
        EvidenceRef(EvidenceDomain.MEMORY, m.id, m.content.take(200), "memory", score)

    // ═══ 1. 峰值工作集受 cap 约束、不随全表规模膨胀 ═══

    @Test
    fun `bounded candidates cap does not grow with corpus size`() {
        val cap = RetrievalKit.DEFAULT_CANDIDATE_CAP
        val model = "text-embedding-v3"
        val vec = FloatArray(8) { it.toFloat() }

        for (size in listOf(100, 1_000, 10_000)) {
            val corpus = (1..size).map { i ->
                FakeMem("m$i", "内容$i", importance = (size - i).toFloat() / size, embedModel = model, embedding = vec)
            }
            val bounded = boundedVectorCandidates(corpus, model, cap)
            // 峰值工作集（余弦重排输入）受 cap 约束，与全表规模无关
            assertTrue("size=$size: bounded ≤ cap", bounded.size <= cap)
            val reranked = cosineRerank(bounded, vec)
            assertTrue("size=$size: rerank 输入 ≤ cap", reranked.size <= cap)
            // 跨域 RRF 输出 ≤ limit（单域等价于 limit）
            val fused = RetrievalKit.rrfFuseEvidence(reranked.map { toRef(it.first, it.second) }, limit = 10)
            assertTrue("size=$size: fused ≤ limit", fused.size <= 10)
        }
    }

    @Test
    fun `full-table scan would load all rows - bounded does not`() {
        val cap = RetrievalKit.DEFAULT_CANDIDATE_CAP
        val model = "text-embedding-v3"
        val vec = FloatArray(4) { 0f }
        val corpus = (1..5_000).map { FakeMem("m$it", "c$it", 0.5f, model, vec) }
        // 旧全表余弦（L-7 债）会载入 5000 条；有界查询只载入 cap
        val bounded = boundedVectorCandidates(corpus, model, cap)
        assertEquals(cap, bounded.size)
        assertTrue(bounded.size < corpus.size)
    }

    // ═══ 2. 召回权衡：cap 内命中 vs 超出 cap 走词法 ═══

    @Test
    fun `recall - relevant in top-cap by importance is found by vector path`() {
        val cap = 5
        val model = "emb"
        val queryVec = floatArrayOf(1f, 0f)
        // 相关项 importance=1.0（最高，进 top-cap）
        val relevant = FakeMem("rel", "用户住在邵阳市双清区", 1.0f, model, floatArrayOf(1f, 0f))
        val corpus = (1..20).map { FakeMem("m$it", "噪音$it", 0.1f, model, floatArrayOf(0f, 1f)) } + relevant
        val bounded = boundedVectorCandidates(corpus, model, cap)
        assertTrue("相关项在 top-cap 内", bounded.any { it.id == "rel" })
        val reranked = cosineRerank(bounded, queryVec)
        assertEquals("rel", reranked.first().first.id)  // 余弦最高
    }

    @Test
    fun `recall - relevant beyond cap is missed by vector path but covered by lexical`() {
        val cap = 5
        val model = "emb"
        val queryVec = floatArrayOf(1f, 0f)
        // 相关项 importance 最低（排在 cap 之外）
        val relevant = FakeMem("rel", "用户住在邵阳市双清区", 0.01f, model, floatArrayOf(1f, 0f))
        val corpus = (1..20).map { FakeMem("m$it", "噪音$it", 0.9f, model, floatArrayOf(0f, 1f)) } + relevant
        val bounded = boundedVectorCandidates(corpus, model, cap)
        assertFalse("相关项在 cap 外，向量路漏", bounded.any { it.id == "rel" })
        // 词法降级链兜底（LIKE 子串命中）
        val lexical = lexicalFallback(corpus, "邵阳")
        assertTrue("词法降级命中相关项", lexical.any { it.id == "rel" })
    }

    // ═══ 3. 索引失效/重建可恢复 ═══

    @Test
    fun `index failure - stale model excluded, lexical fallback then rebuild recovers vector`() {
        val cap = 10
        val oldModel = "emb-v2"
        val newModel = "emb-v3"   // 换档
        val queryVec = floatArrayOf(1f, 0f)
        val corpus = (1..5).map {
            FakeMem("m$it", "内容$it 邵阳", 0.5f, oldModel, floatArrayOf(0f, 1f))
        }
        // 换档后：embed_model != newModel → 向量路空（旧向量被 SQL 排除）
        val bounded = boundedVectorCandidates(corpus, newModel, cap)
        assertTrue("换档后向量路空", bounded.isEmpty())
        // 词法降级兜底
        val lexical = lexicalFallback(corpus, "邵阳")
        assertTrue("词法降级可用", lexical.isNotEmpty())
        // 重建（backfill 重嵌 stale-model → 回写 newModel + 新向量）
        val reembedded = corpus.map { it.copy(embedModel = newModel, embedding = floatArrayOf(1f, 0f)) }
        val boundedAfter = boundedVectorCandidates(reembedded, newModel, cap)
        assertTrue("重嵌后向量路恢复", boundedAfter.isNotEmpty())
        val reranked = cosineRerank(boundedAfter, queryVec)
        assertTrue("重嵌后余弦重排非空", reranked.isNotEmpty())
    }

    @Test
    fun `index failure - no embeddings at all, lexical covers and rebuild recovers`() {
        val cap = 10
        val model = "emb"
        val corpus = (1..5).map { FakeMem("m$it", "邵阳内容$it", 0.5f, embedModel = "", embedding = null) }
        val bounded = boundedVectorCandidates(corpus, model, cap)
        assertTrue("全空向量时向量路空", bounded.isEmpty())
        assertTrue("词法恒可用", lexicalFallback(corpus, "邵阳").isNotEmpty())
        // 重建
        val reembedded = corpus.map { it.copy(embedModel = model, embedding = floatArrayOf(1f, 0f)) }
        assertTrue("重嵌后恢复", boundedVectorCandidates(reembedded, model, cap).isNotEmpty())
    }

    // ═══ 跨域 RRF 不合并写入域（mem + kb 同 id 均保留）═══

    @Test
    fun `cross-domain fusion keeps both domains without merging write domains`() {
        val memRank = listOf(EvidenceRef(EvidenceDomain.MEMORY, "dup", "记忆", "memory", 0.9f))
        val kbRank = listOf(EvidenceRef(EvidenceDomain.KNOWLEDGE, "dup", "知识", "knowledge/n.md", 0.8f))
        val fused = RetrievalKit.rrfFuseEvidence(memRank, kbRank, limit = 10)
        assertEquals(2, fused.size)  // 不合并为 1
        val domains = fused.map { it.first.domain }.toSet()
        assertTrue(domains.contains(EvidenceDomain.MEMORY))
        assertTrue(domains.contains(EvidenceDomain.KNOWLEDGE))
    }
}
