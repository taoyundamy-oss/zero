package com.zero.v2.app.acceptance

import androidx.test.platform.app.InstrumentationRegistry
import com.zero.v2.services.embedding.EmbeddingProvider
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RecallRequestBudgetTest {
    @Test fun failureReservationsSurviveRestartAndLimitBlocksBeforeProviderCall() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File(context.cacheDir, "budget-${java.util.UUID.randomUUID()}.json")
        var calls = 0
        val provider = object : EmbeddingProvider {
            override val modelId = "offline-budget-fixture"
            override suspend fun isAvailable() = true
            override suspend fun embed(text: String): FloatArray? = null
            override suspend fun embedBatch(texts: List<String>): List<FloatArray?> { calls++; return texts.map { null } }
        }
        try {
            RecallRequestBudget(file).provider(provider).embed("test")
            assertEquals(68L, RecallRequestBudget(file).snapshot().getLong("embeddingCharged"))
            file.writeText("""{"embeddingCharged":1499999,"rankRequests":384}""")
            val budget = RecallRequestBudget(file)
            assertTrue(runCatching { budget.provider(provider).embed("test") }.isFailure)
            assertEquals(1, calls)
            assertEquals(1499999L, budget.snapshot().getLong("embeddingCharged"))
            assertEquals(1_500_000L, budget.snapshot().getLong("embeddingLimit"))
        } finally { file.delete() }
    }
}
