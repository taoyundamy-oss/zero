package com.zero.v2.services.agent

import android.content.SharedPreferences
import com.zero.v2.core.common.TokenUsage
import com.zero.v2.services.llm.UsageLedgerFailureStore
import java.math.BigDecimal
import java.time.LocalDate

/** The historical protection estimate is not a provider bill. Unknown prices stay unknown. */
internal class BudgetAccounting(
    private val prefs: SharedPreferences,
    private val failureStore: UsageLedgerFailureStore? = null,
    private val date: () -> LocalDate = { LocalDate.now() },
) {
    private var incomplete = failureStore?.failed() == true
    data class Snapshot(
        val dayTokens: Long,
        val monthTokens: Long,
        val protectionEstimateUsd: BigDecimal,
        val unpricedRequests: Long,
        val localTokens: Long,
        val cachedInput: Long,
        val pricedSubtotalUsd: BigDecimal,
        val includedTokens: Long,
        val legacyUnknown: Boolean,
    ) {
        val priceSource: String = "user_entered_usd_plus_unknown_legacy_estimate"
        val actualCostUsd: BigDecimal? =
            if (unpricedRequests == 0L && !legacyUnknown && includedTokens == 0L) pricedSubtotalUsd else null
    }

    @Synchronized
    fun snapshot(): Snapshot = read(date())

    private fun read(today: LocalDate): Snapshot {
        val sameDay = prefs.getString("day_key", "") == today.toString().replace("-", "")
        val sameMonth = prefs.getString("month_key", "") == java.time.YearMonth.from(today).toString().replace("-", "")
        val estimate = if (!sameMonth) BigDecimal.ZERO else {
            prefs.getString("month_estimate_usd_decimal", null)?.toBigDecimal()
                ?: BigDecimal.valueOf(prefs.getInt("month_usd_cents", 0).toLong(), 2)
        }
        return Snapshot(
            if (sameDay) prefs.getLong("day_tokens", 0) else 0,
            if (sameMonth) prefs.getLong("month_tokens", 0) else 0,
            estimate,
            if (sameMonth) prefs.getLong("month_unpriced_requests", 0) else 0,
            if (sameMonth) prefs.getLong("month_local_tokens", 0) else 0,
            if (sameMonth) prefs.getLong("month_cached_input", 0) else 0,
            if (sameMonth) prefs.getString("month_priced_usd", "0")!!.toBigDecimal() else BigDecimal.ZERO,
            if (sameMonth) prefs.getLong("month_included_tokens", 0) else 0,
            incomplete || (sameMonth && prefs.getBoolean("month_legacy_unknown", prefs.getLong("month_tokens", 0) > 0)),
        )
    }

    @Synchronized
    fun record(usage: TokenUsage, billable: Boolean, costUsd: BigDecimal? = null, included: Boolean = false) {
        var saved = false
        try {
            recordConfirmed(usage, billable, costUsd, included)
            saved = true
        } finally {
            if (!saved) {
                incomplete = true
                failureStore?.mark()
            }
        }
    }

    private fun recordConfirmed(usage: TokenUsage, billable: Boolean, costUsd: BigDecimal?, included: Boolean) {
        val today = date()
        val before = read(today)
        val tokens = usage.totalTokens.coerceAtLeast(0)
        val amount = if (!billable || included) BigDecimal.ZERO else
            costUsd ?: BigDecimal.valueOf(tokens).multiply(BigDecimal("0.000000015"))
        check(prefs.edit()
            .putString("day_key", today.toString().replace("-", ""))
            .putLong("day_tokens", Math.addExact(before.dayTokens, tokens))
            .putString("month_key", java.time.YearMonth.from(today).toString().replace("-", ""))
            .putLong("month_tokens", Math.addExact(before.monthTokens, tokens))
            .putString("month_estimate_usd_decimal", before.protectionEstimateUsd.add(amount).toPlainString())
            .putString("price_source", before.priceSource)
            .putString("month_priced_usd", before.pricedSubtotalUsd.add(
                if (billable && !included) costUsd ?: BigDecimal.ZERO else BigDecimal.ZERO).toPlainString())
            .putBoolean("month_legacy_unknown", before.legacyUnknown)
            .putLong("month_included_tokens", Math.addExact(before.includedTokens, if (included) tokens else 0))
            .putLong("month_unpriced_requests", Math.addExact(before.unpricedRequests,
                if (billable && !included && costUsd == null) 1 else 0))
            .putLong("month_local_tokens", Math.addExact(before.localTokens, if (billable) 0 else tokens))
            .putLong("month_cached_input", Math.addExact(before.cachedInput, usage.cachedTokens.coerceAtLeast(0)))
            .commit()) { "Budget persistence failed" }
    }
}
