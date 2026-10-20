package com.zero.v2.core.common

/**
 * 缓存命中率聚合报告（C3 / 设计§4.1）——固定 cohort 可观测热请求的 token 加权比率。
 *
 * 设计§4.1 验收要求：
 * - 主目标：固定 cohort 可观测热请求的 token 加权比率 ≥98%（[weightedRatio]）。
 * - 报告 P50/P10、样本数、覆盖率、全部 input 的 cached 比例。
 * - **同 cohort 服务端失效、超时和零命中必须保留计数，不能挑选成功样本**（[totalRequests] 含失败/零命中）。
 * - 缺 usage 单列且报告可观测覆盖率（[unknownRequests] + [coverage]）。
 * - 服务商尽力而为导致目标不达时报告真实结果，不通过保留过长历史或改分母"刷指标"。
 *
 * 纯逻辑（[aggregate]）→ JVM 可单测。真实 provider 热请求采集由调用方（C3 验证步骤）执行，
 * 传 [CacheObservation] 列表给本对象聚合。
 */
data class CacheHitReport(
    val cohortId: String,
    /** 总请求数（含失败/零命中/缺 usage——不挑样本）。 */
    val totalRequests: Int,
    /** 有 usage 的请求数（present=true）。 */
    val observedRequests: Int,
    /** 缺 usage 的请求数（present=false——单列 unknown，不当 0%）。 */
    val unknownRequests: Int,
    /** 覆盖率：observed/total（缺 usage 单列报告）。 */
    val coverage: Float,
    /** 观测请求的 input token 总和（分母——不含 unknown）。 */
    val totalInputTokens: Long,
    /** 观测请求的命中 token 总和（分子）。 */
    val totalHitTokens: Long,
    /** token 加权比率：totalHit/totalInput（0-1）。observed=0 → -1（unknown）。 */
    val weightedRatio: Float,
    /** 比率标签：observed=0 → "unknown"；input=0 → "unknown"；否则百分比。 */
    val ratioLabel: String,
    /** P50 input tokens（观测请求的中位数）。 */
    val p50InputTokens: Long,
    /** P10 input tokens（低端 10%——小请求样本）。 */
    val p10InputTokens: Long,
    /** Includes known input counts even where the cache field was absent. */
    val allKnownInputTokens: Long = totalInputTokens,
    val inputCoverage: Float = 0f,
    val knownInputHitLowerBound: Float = -1f,
    val p50HitRatio: Float = -1f,
    val p10HitRatio: Float = -1f,
) {
    /** 是否达标（设计§4.1 主目标 ≥98%）。unknown 无法判定 → false。 */
    fun meets98Target(): Boolean = weightedRatio >= 0.98f
}

object CacheHitReportAggregator {
    /**
     * 聚合一组 cohort 观测 → 报告。
     *
     * @param cohortId 固定 cohort 标识（同 provider/model/角色/prefixVersion）
     * @param observations 每次请求的缓存观测（含失败/零命中/缺 usage——不挑样本）
     */
    fun aggregate(cohortId: String, observations: List<CacheObservation>): CacheHitReport {
        val total = observations.size
        val observed = observations.count { it.present }
        val unknown = total - observed
        val coverage = if (total == 0) 0f else observed.toFloat() / total

        val observedObs = observations.filter { it.present }
        val totalInput = observedObs.sumOf { it.inputTokens }
        val totalHit = observedObs.sumOf { it.hitTokens }
        val ratio = if (observedObs.isEmpty() || totalInput <= 0) -1f else (totalHit.toFloat() / totalInput).coerceIn(0f, 1f)
        val ratioLabel = when {
            observedObs.isEmpty() -> "unknown"
            totalInput <= 0 -> "unknown"
            else -> "${(ratio * 100).toInt()}%"
        }

        val inputs = observedObs.map { it.inputTokens }.sorted()
        val p50 = percentile(inputs, 0.50)
        val p10 = percentile(inputs, 0.10)

        val allInput = observations.sumOf { it.inputTokens.coerceAtLeast(0) }
        val ratios = observedObs.filter { it.inputTokens > 0 }
            .map { it.hitTokens.toFloat() / it.inputTokens }.sorted()

        return CacheHitReport(
            cohortId = cohortId,
            totalRequests = total,
            observedRequests = observed,
            unknownRequests = unknown,
            coverage = coverage,
            totalInputTokens = totalInput,
            totalHitTokens = totalHit,
            weightedRatio = ratio,
            ratioLabel = ratioLabel,
            p50InputTokens = p50,
            p10InputTokens = p10,
            allKnownInputTokens = allInput,
            inputCoverage = if (allInput > 0) totalInput.toFloat() / allInput else 0f,
            knownInputHitLowerBound = if (allInput > 0) totalHit.toFloat() / allInput else -1f,
            p50HitRatio = ratios.getOrNull(((ratios.size - 1) * 0.50).toInt()) ?: -1f,
            p10HitRatio = ratios.getOrNull(((ratios.size - 1) * 0.10).toInt()) ?: -1f,
        )
    }

    /** 简单百分位（线性插值——样本数小时足够供报告）。空列表 → 0。 */
    private fun percentile(sortedValues: List<Long>, p: Double): Long {
        if (sortedValues.isEmpty()) return 0
        if (sortedValues.size == 1) return sortedValues[0]
        val idx = ((sortedValues.size - 1) * p).toInt().coerceIn(0, sortedValues.lastIndex)
        return sortedValues[idx]
    }
}
