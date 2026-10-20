package com.zero.v2.core.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [CacheHitReportAggregator] 单测 — C3 验收：缓存命中率聚合（不挑样本 + 覆盖率 + P50/P10 + unknown）。
 *
 * 设计§4.1：≥98% 主目标；失败/零命中保留计数；缺 usage 单列 unknown；不改分母刷指标。
 */
class CacheHitReportTest {

    private fun obs(hit: Long, miss: Long, input: Long, present: Boolean = true) =
        CacheObservation(present = present, hitTokens = hit, missTokens = miss,
            inputTokens = input, outputTokens = 0, totalTokens = input)

    @Test
    fun perfectCacheHit_meets98Target() {
        // 10 次热请求，每次 input=1000 hit=980（98%）→ 加权 98% 达标
        val observations = List(10) { obs(980, 20, 1000) }
        val r = CacheHitReportAggregator.aggregate("cohort1", observations)
        assertEquals(10, r.totalRequests)
        assertEquals(10, r.observedRequests)
        assertEquals(0, r.unknownRequests)
        assertEquals(9800, r.totalHitTokens)
        assertEquals(10000, r.totalInputTokens)
        assertEquals(0.98f, r.weightedRatio, 0.001f)
        assertEquals("98%", r.ratioLabel)
        assertTrue(r.meets98Target())
        assertEquals(1.0f, r.coverage, 0.001f)
    }

    @Test
    fun belowTarget_reportsTrueRatio_notInflated() {
        // 50% 命中——报告真实 50%，不达标
        val observations = List(5) { obs(500, 500, 1000) }
        val r = CacheHitReportAggregator.aggregate("cohort2", observations)
        assertEquals(0.5f, r.weightedRatio, 0.001f)
        assertEquals("50%", r.ratioLabel)
        assertFalse(r.meets98Target())
    }

    @Test
    fun zeroHitSamples_retained_notDropped() {
        // 设计§4.1：零命中必须保留计数，不能挑选成功样本
        val observations = listOf(
            obs(980, 20, 1000),   // 98% 命中
            obs(0, 1000, 1000),   // 0% 命中（零命中——保留）
            obs(980, 20, 1000),
        )
        val r = CacheHitReportAggregator.aggregate("cohort3", observations)
        assertEquals(3, r.totalRequests)  // 零命中样本计入
        assertEquals(1960, r.totalHitTokens)
        assertEquals(3000, r.totalInputTokens)
        assertEquals(0.653f, r.weightedRatio, 0.01f)  // 1960/3000 ≈ 65.3%
    }

    @Test
    fun missingUsage_listedAsUnknown_notAs0pct() {
        // 设计§4.1：缺 usage 单列 unknown，不当 0%
        val observations = listOf(
            obs(980, 20, 1000, present = true),
            CacheObservation.ABSENT,  // 缺 usage
            obs(980, 20, 1000, present = true),
        )
        val r = CacheHitReportAggregator.aggregate("cohort4", observations)
        assertEquals(3, r.totalRequests)
        assertEquals(2, r.observedRequests)
        assertEquals(1, r.unknownRequests)
        assertEquals(2f / 3f, r.coverage, 0.01f)  // 2/3 覆盖率
        // 比率只算 observed（不含 unknown）——不把缺 usage 当 0% 拉低分母
        assertEquals(0.98f, r.weightedRatio, 0.001f)
        assertTrue(r.meets98Target())
    }

    @Test
    fun allUnknown_noInflatedRatio() {
        // 全缺 usage → unknown，不报 0% 也不报 100%
        val observations = List(5) { CacheObservation.ABSENT }
        val r = CacheHitReportAggregator.aggregate("cohort5", observations)
        assertEquals(0, r.observedRequests)
        assertEquals(5, r.unknownRequests)
        assertEquals(0f, r.coverage, 0.001f)
        assertEquals(-1f, r.weightedRatio)
        assertEquals("unknown", r.ratioLabel)
        assertFalse(r.meets98Target())
    }

    @Test
    fun p50p10_reported() {
        // input tokens 分布 [100,200,...,1000]——P50≈550, P10≈100ish
        val observations = (1..10).map { obs(it * 100L, 0, it * 100L) }
        val r = CacheHitReportAggregator.aggregate("cohort6", observations)
        assertTrue(r.p50InputTokens > 0)
        assertTrue(r.p10InputTokens <= r.p50InputTokens)  // P10 ≤ P50
        assertEquals(100L, r.p10InputTokens)  // 最低端
    }

    @Test
    fun weightedRatio_tokenWeighted_notAveraged() {
        // 设计§4.1：token 加权比率（非简单平均）——大请求的命中率权重更大
        val observations = listOf(
            obs(100, 0, 100),     // 100% 命中（小请求）
            obs(0, 10000, 10000), // 0% 命中（大请求）
        )
        val r = CacheHitReportAggregator.aggregate("cohort7", observations)
        // 加权：100/10100 ≈ 0.99%（非平均的 50%）
        assertEquals(0.0099f, r.weightedRatio, 0.001f)
        assertFalse(r.meets98Target())
    }
    @Test fun partialCacheTelemetryRetainsKnownInputWithoutPretendingMiss() {
        val unknown = CacheObservation.fromUsage(false, 100, 0, 0, 10, 110)
        val report = CacheHitReportAggregator.aggregate("same-model-hot", listOf(obs(90, 10, 100), unknown))
        assertEquals(100L, unknown.inputTokens)
        assertEquals("unknown", unknown.ratioLabel)
        assertEquals(200L, report.allKnownInputTokens)
        assertEquals(.9f, report.weightedRatio, .001f)
        assertEquals(.5f, report.inputCoverage, .001f)
        assertEquals(.45f, report.knownInputHitLowerBound, .001f)
        assertEquals(.9f, report.p50HitRatio, .001f)
        assertEquals(1, report.unknownRequests)
    }

}
