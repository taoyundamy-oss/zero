package com.zero.v2.services.retrieval

import com.zero.v2.core.common.EvidenceDomain
import com.zero.v2.core.common.EvidenceRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** R2：RetrievalKit 检索原语 + 跨域证据 RRF/去重 纯逻辑测试。 */
class RetrievalKitTest {
    @Test fun cosineRemainsFiniteForLargeAndTinyFiniteVectors() {
        for (scale in listOf(Float.MAX_VALUE, Float.MIN_VALUE, 1f)) {
            val a = floatArrayOf(scale, scale)
            assertEquals(1f, RetrievalKit.cosineSimilarity(a, a), 0.00001f)
            assertEquals(-1f, RetrievalKit.cosineSimilarity(a, floatArrayOf(-scale, -scale)), 0.00001f)
        }
    }

    @Test fun cosineRejectsNonFiniteValuesAndHandlesZeroOrDimensionMismatch() {
        assertTrue(RetrievalKit.cosineSimilarity(floatArrayOf(Float.NaN), floatArrayOf(1f)).isNaN())
        assertEquals(0f, RetrievalKit.cosineSimilarity(floatArrayOf(0f), floatArrayOf(1f)), 0f)
        assertEquals(0f, RetrievalKit.cosineSimilarity(floatArrayOf(1f), floatArrayOf(1f, 2f)), 0f)
    }

    private fun mem(id: String, content: String = "m-$id") =
        EvidenceRef(EvidenceDomain.MEMORY, id, content, "memory")
    private fun kb(id: String, content: String = "k-$id") =
        EvidenceRef(EvidenceDomain.KNOWLEDGE, id, content, "knowledge/note.md")

    // ═══ 跨域证据 RRF（R2 核心）═══

    @Test
    fun `cross-domain same id does not collide in rrfFuseEvidence`() {
        // mem:x 与 kb:x 同 id 不同域 → 融合后均保留（不撞键）
        val memRanking = listOf(mem("x"), mem("y"))
        val kbRanking = listOf(kb("x"), kb("z"))
        val fused = RetrievalKit.rrfFuseEvidence(memRanking, kbRanking, limit = 10)
        val keys = fused.map { it.first.key() }
        assertTrue("mem:x 与 kb:x 均应保留", keys.containsAll(listOf("mem:x", "kb:x")))
        assertEquals(4, fused.size)
    }

    @Test
    fun `intra-domain duplicate merges score`() {
        // 同一 mem:x 在两路 ranking 出现 → 融合为单条、分数累加（去重）
        val r1 = listOf(mem("x"))
        val r2 = listOf(mem("x"))
        val fused = RetrievalKit.rrfFuseEvidence(r1, r2, limit = 10)
        assertEquals(1, fused.size)
        assertEquals("mem:x", fused.first().first.key())
        // 两路 rank0 → 2 * 1/(K+1)
        val expected = 2f * (1f / (RetrievalKit.RRF_K + 1))
        assertEquals(expected, fused.first().second, 0.0001f)
    }

    @Test
    fun `higher rank yields higher score`() {
        val ranking = listOf(mem("a"), mem("b"), mem("c"))
        val fused = RetrievalKit.rrfFuseEvidence(ranking, limit = 10)
        // 单路：rank0 > rank1 > rank2
        assertTrue(fused[0].second > fused[1].second)
        assertTrue(fused[1].second > fused[2].second)
        assertEquals("mem:a", fused[0].first.key())
    }

    @Test
    fun `rrfFuseEvidence respects limit`() {
        val ranking = (1..50).map { mem(it.toString()) }
        val fused = RetrievalKit.rrfFuseEvidence(ranking, limit = 5)
        assertEquals(5, fused.size)
    }

    @Test
    fun `empty rankings yield empty result`() {
        val fused = RetrievalKit.rrfFuseEvidence(emptyList(), emptyList(), limit = 10)
        assertTrue(fused.isEmpty())
    }

    @Test
    fun `DEFAULT_CANDIDATE_CAP is 100`() {
        assertEquals(100, RetrievalKit.DEFAULT_CANDIDATE_CAP)
    }

    // ═══ 检索原语回归（KB1.2 纯搬家后行为零变化）═══

    @Test
    fun `cosineSimilarity identical is one`() {
        val v = floatArrayOf(1f, 2f, 3f)
        assertEquals(1f, RetrievalKit.cosineSimilarity(v, v), 0.0001f)
    }

    @Test
    fun `cosineSimilarity orthogonal is zero`() {
        val a = floatArrayOf(1f, 0f)
        val b = floatArrayOf(0f, 1f)
        assertEquals(0f, RetrievalKit.cosineSimilarity(a, b), 0.0001f)
    }

    @Test
    fun `cosineSimilarity opposite is minus one`() {
        val a = floatArrayOf(1f, 2f)
        val b = floatArrayOf(-1f, -2f)
        assertEquals(-1f, RetrievalKit.cosineSimilarity(a, b), 0.0001f)
    }

    @Test
    fun `encode and decode embedding round-trip`() {
        val original = floatArrayOf(0.1f, -0.2f, 0.3f, 0.0f, 1.5f)
        val decoded = RetrievalKit.decodeEmbedding(RetrievalKit.encodeEmbedding(original))
        assertEquals(original.size, decoded.size)
        for (i in original.indices) assertEquals(original[i], decoded[i], 0.0001f)
    }

    @Test
    fun `queryBigrams strips punctuation and windows to 2`() {
        val bigrams = RetrievalKit.queryBigrams("邵阳 天气，如何？")
        // 去空白与标点后 "邵阳天气如何" → 邵阳 阳天 天气 气如 如何
        assertTrue(bigrams.contains("邵阳"))
        assertTrue(bigrams.contains("天气"))
        assertTrue(bigrams.none { it.contains(" ") || it.contains("，") || it.contains("？") })
        assertTrue(bigrams.size <= 8)
    }

    @Test
    fun `queryBigrams distinct`() {
        val bigrams = RetrievalKit.queryBigrams("ababab")
        // ab/ba/ab/ba/ab → distinct = ab, ba
        assertEquals(listOf("ab", "ba"), bigrams)
    }
}
