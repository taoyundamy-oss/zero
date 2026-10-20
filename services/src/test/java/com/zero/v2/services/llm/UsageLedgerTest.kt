package com.zero.v2.services.llm

import com.zero.v2.core.common.TokenUsage
import com.zero.v2.core.data.dao.UsageRequestDao
import com.zero.v2.core.data.entity.UsageRequestEntity
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UsageLedgerTest {
    @get:org.junit.Rule val folder = org.junit.rules.TemporaryFolder()
    private val profile = LlmProfile("p", "p", "https://unused.test", "secret-ref", modelId = "m")

    @Test fun `concurrent terminals and reconstruction retain known zero versus unknown`() = runBlocking<Unit> {
        val dao = MemoryDao()
        val ledger = UsageLedger(dao)
        (0..19).map { i -> async {
            ledger.begin("$i", profile, "stream", "append_only", "observed_prefix")
            ledger.finish("$i", "completed", TokenUsage(inputTokens = 100, outputTokens = 5,
                cacheObservedInputTokens = 100))
        } }.awaitAll()
        val restored = UsageLedger(dao)
        restored.begin("failed", profile, "sync", "independent", "conservative")
        restored.finish("failed", "failed", null)
        assertEquals(21, dao.rows.size)
        assertEquals(0L, dao.rows["0"]!!.cachedTokens)
        assertNull(dao.rows["failed"]!!.cachedTokens)
        assertNull(dao.rows["failed"]!!.inputTokens)
    }

    @Test fun `explicit zero missing fields and terminal retries remain distinct`() = runBlocking<Unit> {
        val dao = MemoryDao()
        val ledger = UsageLedger(dao)
        ledger.begin("zero", profile, "sync", "independent", "conservative")
        ledger.finish("zero", "completed", ProviderUsage.read(org.json.JSONObject()
            .put("prompt_tokens", 0).put("prompt_cache_hit_tokens", 0)))
        ledger.finish("zero", "failed", null)
        assertEquals(0L, dao.rows["zero"]!!.cachedTokens)
        assertNull(dao.rows["zero"]!!.outputTokens)
        assertEquals("completed", dao.rows["zero"]!!.status)
        ledger.begin("partial", profile, "stream", "append_only", "observed_prefix")
        ledger.finish("partial", "responses_incomplete", TokenUsage(inputTokens = 100,
            cachedTokens = 90, cacheObservedInputTokens = 100, outputObserved = false))
        assertEquals("failed", dao.rows["partial"]!!.status)
        assertEquals(90L, dao.rows["partial"]!!.cachedTokens)
    }

    @Test fun `storage failure is visible without breaking inference`() = runBlocking<Unit> {
        val dao = MemoryDao().apply { broken = true }
        val ledger = UsageLedger(dao)
        ledger.begin("x", profile, "stream", "first_request", "no_observation")
        assertTrue(ledger.incomplete.value)
    }

    @Test fun `storage failure warning survives ledger reconstruction`() = runBlocking<Unit> {
        val dao = MemoryDao().apply { broken = true }
        val marker = java.io.File(folder.root, "incomplete")
        val ledger = UsageLedger(dao, UsageLedgerFailureStore(marker))
        assertTrue(!ledger.incomplete.value)
        ledger.begin("lost", profile, "stream", "first_request", "no_observation")
        dao.broken = false
        val restored = UsageLedger(dao, UsageLedgerFailureStore(marker))
        assertTrue(restored.incomplete.value)
        restored.begin("ok", profile, "stream", "first_request", "no_observation")
        assertTrue(restored.incomplete.value)
        assertEquals(0L, marker.length())
    }

    private class MemoryDao : UsageRequestDao {
        val rows = linkedMapOf<String, UsageRequestEntity>()
        var broken = false
        override suspend fun insert(row: UsageRequestEntity) {
            if (broken) throw java.io.IOException("disk")
            rows.putIfAbsent(row.id, row)
        }
        override suspend fun find(id: String) = rows[id]
        override suspend fun finish(id: String, at: Long, status: String, input: Long?, cached: Long?, output: Long?) {
            val row = rows[id] ?: return
            if (row.status == "prepared") rows[id] = row.copy(finishedAt = at, status = status,
                inputTokens = input, cachedTokens = cached, outputTokens = output)
        }
        override fun buckets(start: Long, end: Long, connection: String, model: String, daily: Boolean) =
            flowOf(emptyList<com.zero.v2.core.data.dao.UsageBucket>())
        override fun details(start: Long, end: Long, connection: String, model: String, offset: Int) =
            flowOf(rows.values.toList())
        override fun routes() = flowOf(emptyList<com.zero.v2.core.data.dao.UsageRoute>())
        override fun observe(start: Long, end: Long): Flow<List<UsageRequestEntity>> = flowOf(rows.values.toList())
    }
}
