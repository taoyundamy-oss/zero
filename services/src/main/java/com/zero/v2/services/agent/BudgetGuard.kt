package com.zero.v2.services.agent

import android.content.Context
import com.zero.v2.core.common.TokenUsage
import com.zero.v2.core.data.store.ModuleConfigStore
import com.zero.v2.core.trace.AgentTraceManager
import com.zero.v2.core.trace.NodeType
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 预算守卫 — 开发前规范化 N5（设计 §7.4 #4 / §八：预算值强制执行）。
 *
 * 计量：OpenAiCompatibleClient 每次调用后 record()（chat/TTS/embedding 全 API 咽喉），
 *       按日/月串行累计并确认持久化（SharedPreferences，跨进程重启）。
 * 执法：ChatBotModule FC 每轮前 / AutomationModule 运行前 preCheck()——超支终止+可观测。
 * fail-open（X8）：守护自身故障（存储读失败等）一律放行 + warn trace，绝不砖死对话。
 *
 * 预算源：ModuleConfigStore["agent_behavior"]（AgentBehaviorScreen 同源；默认 日 20M / 月 $10，§八实测订正）。
 * 成本估算：chat+TTS+embed 混合 ¥≈$0.015/M tokens（V4 Flash 实测 7.8M≈$0.11 外推，粗估仅作月费判据）。
 */
@Singleton
class BudgetGuard @Inject constructor(
    @ApplicationContext private val context: Context,
    private val moduleConfigStore: ModuleConfigStore,
    private val traceManager: AgentTraceManager
) {
    enum class Verdict { OK, DAILY_EXCEEDED, MONTHLY_EXCEEDED }

    private val accounting by lazy {
        BudgetAccounting(context.getSharedPreferences("zero_budget_guard", Context.MODE_PRIVATE),
            com.zero.v2.services.llm.UsageLedgerFailureStore(
                java.io.File(context.noBackupFilesDir, "budget-incomplete")))
    }

    /** Serialized, decimal, confirmed persistence; local calls only consume token limits. */
    fun record(usage: TokenUsage, billable: Boolean = true,
        costUsd: java.math.BigDecimal? = null, included: Boolean = false) {
        try {
            accounting.record(usage, billable, costUsd, included)
        } catch (_: Exception) {
            traceManager.record("budget", NodeType.ERROR, "budget_guard", "record_failed: accounting incomplete")
        }
    }

    /**
     * 预算检查（FC 每轮 / 自动化运行前调用）。
     * fail-open：任何异常 → OK + warn（守护故障绝不砖死对话，X8）。
     */
    suspend fun preCheck(caller: String): Verdict = withContext(Dispatchers.IO) {
        try {
            val (dailyLimit, monthlyLimitUsd) = readLimits()
            val totals = accounting.snapshot()
            val dayTokens = totals.dayTokens
            val monthUsd = totals.protectionEstimateUsd.toDouble()
            return@withContext when {
                monthUsd >= monthlyLimitUsd -> Verdict.MONTHLY_EXCEEDED
                dayTokens >= dailyLimit -> Verdict.DAILY_EXCEEDED
                else -> Verdict.OK
            }
        } catch (e: Exception) {
            traceManager.record("budget", NodeType.ERROR, "budget_guard",
                "precheck_fail_open: caller=$caller ${e.message?.take(40)}")
            Verdict.OK
        }
    }

    /** 当前累计（UI/诊断用；fail-open 返回 0） */
    fun snapshot(): Triple<Long, Long, Double> = try {
        val totals = accounting.snapshot()
        Triple(totals.dayTokens, totals.monthTokens, totals.protectionEstimateUsd.toDouble())
    } catch (_: Exception) { Triple(0, 0, 0.0) }

    fun pricingSummary(): String = try {
        val totals = accounting.snapshot()
        "本月按用户填写价格累计：$${totals.pricedSubtotalUsd.toPlainString()}；未定价请求 ${totals.unpricedRequests}。" +
            "套餐内 tokens ${totals.includedTokens}；历史或存储失败造成的费用缺口=${totals.legacyUnknown}。" +
            "价格单位 USD/百万 tokens，非服务商账单。"
    } catch (_: Exception) { "预算计费读取失败，请重试" }

    suspend fun readLimits(): Pair<Long, Float> = try {
        val json = moduleConfigStore.get("agent_behavior")
        if (json != null) {
            val obj = JSONObject(json)
            // 与 AgentBehaviorViewModel.fromJson 同界钳位（§八）
            Pair(obj.optLong("dailyTokenLimit", 20_000_000L).coerceIn(8_000_000L, 100_000_000L),
                obj.optDouble("monthlyCostLimitUsd", 10.0).toFloat().coerceIn(5.0f, 50.0f))
        } else Pair(20_000_000L, 10.0f)
    } catch (_: Exception) { Pair(20_000_000L, 10.0f) }
}
