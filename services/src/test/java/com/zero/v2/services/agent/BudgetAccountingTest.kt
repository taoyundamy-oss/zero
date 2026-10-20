package com.zero.v2.services.agent

import android.content.SharedPreferences
import com.zero.v2.core.common.TokenUsage
import java.lang.reflect.Proxy
import java.time.LocalDate
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BudgetAccountingTest {
    @Test fun failureMarkerSurvivesRestartBeforeAnotherSuccessfulWrite() {
        val directory = java.nio.file.Files.createTempDirectory("budget-failure").toFile()
        try {
            val store = com.zero.v2.services.llm.UsageLedgerFailureStore(java.io.File(directory, "incomplete"))
            val prefs = preferences(mutableMapOf()) { false }
            val ledger = BudgetAccounting(prefs, store)
            try { ledger.record(TokenUsage(totalTokens = 1), true) } catch (_: IllegalStateException) { }
            val recovered = BudgetAccounting(preferences(mutableMapOf()), store).snapshot()
            assertTrue(recovered.legacyUnknown)
            assertNull(recovered.actualCostUsd)
        } finally { directory.listFiles()?.forEach { it.delete() }; directory.delete() }
    }

    @Test fun failedCommitRemainsVisibleAfterRecoveryAndRestart() {
        val data = mutableMapOf<String, Any>()
        var reject = true
        val prefs = preferences(data) { !reject }
        val ledger = BudgetAccounting(prefs) { LocalDate.of(2026, 10, 13) }
        try { ledger.record(TokenUsage(totalTokens = 1), true) } catch (_: IllegalStateException) { }
        // A later successful record must not claim that the missing write never happened.
        reject = false
        ledger.record(TokenUsage(totalTokens = 1), true, java.math.BigDecimal.ZERO)
        assertTrue(ledger.snapshot().legacyUnknown)
        assertTrue(BudgetAccounting(prefs) { LocalDate.of(2026, 10, 13) }.snapshot().legacyUnknown)
    }
    @Test fun pricedCallsKeepHistoricAmountsAndUnknownDoesNotBecomeFree() {
        val ledger = BudgetAccounting(preferences(mutableMapOf())) { LocalDate.of(2026, 10, 13) }
        ledger.record(TokenUsage(totalTokens = 100), true, "0.000123456789012".toBigDecimal())
        ledger.record(TokenUsage(totalTokens = 100), true, "0.0002".toBigDecimal())
        assertEquals("0.000323456789012", ledger.snapshot().actualCostUsd!!.toPlainString())
        ledger.record(TokenUsage(totalTokens = 100), true)
        assertNull(ledger.snapshot().actualCostUsd)
        assertEquals(1L, ledger.snapshot().unpricedRequests)
        ledger.record(TokenUsage(totalTokens = 100), true, included = true)
        assertEquals(100L, ledger.snapshot().includedTokens)
        assertEquals("0.000323456789012", ledger.snapshot().pricedSubtotalUsd.toPlainString())
    }

    @Test fun smallConcurrentCallsPersistWithoutRoundingAndLocalIsSeparate() {
        val data = mutableMapOf<String, Any>()
        val prefs = preferences(data)
        val ledger = BudgetAccounting(prefs) { LocalDate.of(2026, 10, 13) }
        val workers = Executors.newFixedThreadPool(8)
        repeat(1000) { workers.submit { ledger.record(TokenUsage(totalTokens = 1, cachedTokens = 1), true) } }
        workers.shutdown()
        assertTrue(workers.awaitTermination(10, TimeUnit.SECONDS))
        ledger.record(TokenUsage(totalTokens = 500), false)
        val restored = BudgetAccounting(prefs) { LocalDate.of(2026, 10, 13) }.snapshot()
        assertEquals(1500L, restored.dayTokens)
        assertEquals(0, "0.000015".toBigDecimal().compareTo(restored.protectionEstimateUsd))
        assertEquals(1000L, restored.unpricedRequests)
        assertEquals(500L, restored.localTokens)
        assertEquals(1000L, restored.cachedInput)
        assertNull(restored.actualCostUsd)
    }

    @Test fun legacyMigrationAndCalendarBoundariesKeepExistingMoney() {
        val data = mutableMapOf<String, Any>("day_key" to "20261013", "day_tokens" to 100L,
            "month_key" to "202610", "month_tokens" to 200L, "month_usd_cents" to 125)
        var date = LocalDate.of(2026, 10, 13)
        val ledger = BudgetAccounting(preferences(data)) { date }
        ledger.record(TokenUsage(totalTokens = 1), true)
        assertEquals("1.250000015", ledger.snapshot().protectionEstimateUsd.toPlainString())
        date = date.plusDays(1)
        assertEquals(0L, ledger.snapshot().dayTokens)
        assertEquals(201L, ledger.snapshot().monthTokens)
        ledger.record(TokenUsage(totalTokens = 1), true)
        date = LocalDate.of(2026, 11, 1)
        assertEquals(0L, ledger.snapshot().monthTokens)
        ledger.record(TokenUsage(totalTokens = 1), true)
        assertEquals("0.000000015", ledger.snapshot().protectionEstimateUsd.toPlainString())
        assertEquals(125, data["month_usd_cents"])
    }

    private fun preferences(
        data: MutableMap<String, Any>, commit: () -> Boolean = { true },
    ): SharedPreferences = Proxy.newProxyInstance(
        SharedPreferences::class.java.classLoader, arrayOf(SharedPreferences::class.java),
    ) { _, method, args ->
        when (method.name) {
            "getString", "getLong", "getInt", "getBoolean" -> data[args!![0]] ?: args[1]
            "edit" -> {
                val pending = mutableMapOf<String, Any>()
                Proxy.newProxyInstance(SharedPreferences.Editor::class.java.classLoader,
                    arrayOf(SharedPreferences.Editor::class.java)) { editor, operation, values ->
                    when (operation.name) {
                        "putString", "putLong", "putBoolean" -> { pending[values!![0] as String] = values[1]; editor }
                        "commit" -> { if (commit()) { data.putAll(pending); true } else false }
                        else -> error(operation.name)
                    }
                }
            }
            else -> error(method.name)
        }
    } as SharedPreferences
}
