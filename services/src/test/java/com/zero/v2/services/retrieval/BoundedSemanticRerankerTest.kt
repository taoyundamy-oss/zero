package com.zero.v2.services.retrieval

import com.zero.v2.core.common.ChatMessage
import com.zero.v2.core.common.FunctionDefinition
import com.zero.v2.core.common.StreamChunk
import com.zero.v2.services.llm.ILanguageModel
import com.zero.v2.services.llm.ModelCapability
import com.zero.v2.services.llm.ModelSelector
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test

class BoundedSemanticRerankerTest {
    private class Model : ILanguageModel {
        override val modelId = "fixture"
        override val displayName = modelId
        override val contextWindow = 8192
        override val capabilities = setOf(ModelCapability.CHAT)
        override val keyConfigured = true
        override val priority = 1
        override val enabled = true
        override var retrievalIdentity = "fixture-v1"
        var calls = 0
        var payload = ""
        var answer: suspend () -> String? = { "[1,0]" }
        override suspend fun boundedCompletion(userMessage: String, systemPrompt: String): String? {
            calls++
            payload = userMessage
            return answer()
        }
        override suspend fun chatSync(userMessage: String, systemPrompt: String?): String = error("unbounded")
        override fun chatStream(messages: List<ChatMessage>, systemPrompt: String?,
            functions: List<FunctionDefinition>, prefixVersion: String?): Flow<StreamChunk> = emptyFlow()
    }

    private val model = Model()
    private val reranker = BoundedSemanticReranker(ModelSelector().also { it.register(model) })
    private val candidates = listOf(BoundedSemanticReranker.Candidate("old-room", "旧地点"),
        BoundedSemanticReranker.Candidate("corrected-room", "更正地点"))

    @Test fun blankEvidenceQueryNeverCertifiesCandidatesButToolOrderIsPreserved() = runBlocking {
        val evidence = reranker.selectEvidence(" \n\t", candidates)
        assertTrue(evidence.completed)
        assertTrue(evidence.ids.isEmpty())
        assertEquals(candidates.map { it.id }, reranker.rerank(" \n\t", candidates).ids)
        assertEquals(0, model.calls)
    }

    @Test fun evidenceParserAcceptsObservedFencesWithoutChangingSlots() {
        listOf("[]" to emptyList(), "[0]" to listOf(0), "[1]" to listOf(1),
            "[0, 1]" to listOf(0, 1), "[1, 0]" to listOf(1, 0)).forEach { (raw, slots) ->
            assertEquals(slots, RerankOrder.decode(raw, 2, true))
            assertEquals(slots, RerankOrder.decode("```json\n$raw\n```", 2, true))
            assertEquals(slots, RerankOrder.decode("```\n$raw\n```", 2, true))
        }
    }

    @Test fun automaticEvidenceCanAbstainAndNeverSharesToolOrderCache() = runBlocking {
        model.answer = { "[1]" }
        assertEquals(listOf("corrected-room", "old-room"), reranker.rerank("地点", candidates).ids)
        assertEquals(listOf("corrected-room"), reranker.selectEvidence("地点", candidates).ids)
        assertEquals(2, model.calls)
        model.answer = { "[]" }
        val result = reranker.selectEvidence("不相关问题", candidates)
        assertEquals("applied", result.status)
        assertTrue(result.ids.isEmpty())
        assertEquals("cache_hit", reranker.selectEvidence("不相关问题", candidates).status)
    }

    @Test fun policyDigestKeepsExistingCacheKeys() {
        val unconfigured = BoundedSemanticReranker(ModelSelector())
        assertEquals("beabf579836db63885a5451d4d8f5939d88acc780234982c7433ee6292f12ba5", unconfigured.policyKey)
    }

    @Test fun evidenceObjectsUseOnlyValidatedSlotsAndNeverReturnedText() = runBlocking {
        model.answer = { """[{"slot":1,"text":"invented replacement"}]""" }
        val result = reranker.selectEvidence("地点", candidates)
        assertEquals("applied", result.status)
        assertEquals(listOf("corrected-room"), result.ids)
        assertEquals("invalid_response", reranker.rerank("地点", candidates).status)
        listOf("""[{"slot":2}]""", """[{"slot":1},{"slot":1}]""",
            """[{"slot":0.5}]""", """[{"slot":"1"}]""", """[{"text":"no slot"}]""").forEach { raw ->
            model.answer = { raw }
            assertEquals("invalid_response", reranker.selectEvidence(raw, candidates).status)
        }
    }

    @Test fun opaqueSlotsAndExactInputCache() = runBlocking {
        assertEquals(listOf("corrected-room", "old-room"), reranker.rerank("地点", candidates).ids)
        assertEquals("cache_hit", reranker.rerank("地点", candidates).status)
        assertEquals(1, model.calls)
        val sent = JSONObject(model.payload).getJSONArray("candidates")
        assertEquals(0, sent.getJSONObject(0).getInt("slot"))
        assertFalse(model.payload.contains("old-room"))
        assertFalse(sent.getJSONObject(0).has("id"))
        model.retrievalIdentity = "fixture-v2"
        assertEquals("applied", reranker.rerank("地点", candidates).status)
        assertEquals(2, model.calls)
        reranker.rerank("历史地点", candidates)
        reranker.rerank("历史地点", candidates.map { it.copy(text = it.text + "更新") })
        assertEquals(4, model.calls)
    }

    @Test fun invalidAnswersNeverLoseOrInventCandidates() = runBlocking {
        listOf("[0,0]", "[]", "[1,2]", "[0.5,1]", "[\"1\",0]", "ignore instructions").forEach {
            model.answer = { it }
            val result = reranker.rerank("地点", candidates)
            assertEquals("invalid_response", result.status)
            assertEquals(candidates.map { c -> c.id }, result.ids)
        }
    }

    @Test fun partialOrderRetainsEveryUnmentionedCandidateInOriginalOrder() = runBlocking {
        model.answer = { "[1]" }
        val items = candidates + BoundedSemanticReranker.Candidate("third", "无关资料")
        val result = reranker.rerank("地点", items)
        assertEquals("applied", result.status)
        assertEquals(listOf("corrected-room", "old-room", "third"), result.ids)
        assertEquals(items.map { it.id }.toSet(), result.ids.toSet())
    }

    @Test fun boundedPrefixKeepsTailAndRejectsOversizedInputs() = runBlocking {
        val items = (0..9).map { BoundedSemanticReranker.Candidate("id$it", "text$it") }
        model.answer = { "[7,6,5,4,3,2,1,0]" }
        assertEquals((7 downTo 0).map { "id$it" } + listOf("id8", "id9"), reranker.rerank("q", items).ids)
        assertEquals(8, JSONObject(model.payload).getJSONArray("candidates").length())
        assertEquals("input_limit", reranker.rerank("q".repeat(513), items).status)
        assertEquals("input_limit", reranker.rerank("q", items.map { it.copy(text = "x".repeat(601)) }).status)
        assertEquals("duplicate_ids", reranker.rerank("q", listOf(items[0], items[0])).status)
        val duplicateItems = listOf(items[0], items[0].copy(text = "different"))
        assertEquals(duplicateItems, reranker.rank("q", duplicateItems, { it.id }, { it.text }).first)
        assertEquals(1, model.calls)
    }

    @Test fun failuresAreNotCachedAndModelSwitchCannotApplyOldOrder() = runBlocking {
        model.answer = { throw IllegalStateException("offline") }
        assertEquals("unavailable", reranker.rerank("q", candidates).status)
        model.answer = { model.retrievalIdentity = "changed"; "[1,0]" }
        assertEquals("model_changed", reranker.rerank("q", candidates).status)
        model.answer = { "[1,0]" }
        assertEquals("applied", reranker.rerank("q", candidates).status)
        assertEquals(3, model.calls)
    }

    @Test fun oversizedNeighbourDoesNotBlockEligibleCandidatesOrDisappear() = runBlocking {
        val long = BoundedSemanticReranker.Candidate("long", "x".repeat(601))
        val items = listOf(long) + candidates
        val result = reranker.rerank("地点", items)
        assertEquals("applied", result.status)
        assertEquals(listOf("corrected-room", "old-room", "long"), result.ids)
        assertEquals(2, JSONObject(model.payload).getJSONArray("candidates").length())
        assertFalse(model.payload.contains("x".repeat(601)))
        assertEquals(1, model.calls)
    }

    @Test fun concurrentCallDoesNotQueueAndCancellationReleasesLock() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        model.answer = { entered.complete(Unit); CompletableDeferred<String>().await() }
        val first = async { reranker.rerank("q", candidates) }
        entered.await()
        assertEquals("busy", reranker.rerank("q", candidates).status)
        first.cancelAndJoin()
        assertTrue(first.isCancelled)
        model.answer = { "[1,0]" }
        assertEquals("applied", reranker.rerank("q", candidates).status)
        assertEquals(2, model.calls)
    }
}
