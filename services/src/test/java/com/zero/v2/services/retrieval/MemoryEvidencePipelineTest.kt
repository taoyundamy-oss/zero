package com.zero.v2.services.retrieval

import com.zero.v2.core.common.ChatMessage
import com.zero.v2.core.common.FunctionDefinition
import com.zero.v2.core.common.StreamChunk
import com.zero.v2.core.data.entity.MemoryEntity
import com.zero.v2.core.data.entity.MemoryEvidenceSnapshot
import com.zero.v2.core.data.entity.MessageEntity
import com.zero.v2.services.llm.ILanguageModel
import com.zero.v2.services.llm.ModelCapability
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryEvidencePipelineTest {
    private class Model : ILanguageModel {
        override val modelId = "fixture"
        override val displayName = modelId
        override var contextWindow = 8192
        override val capabilities = setOf(ModelCapability.CHAT)
        override val keyConfigured = true
        override val priority = 1
        override val enabled = true
        override var retrievalIdentity = "fixture-v1"
        var calls = 0
        var payload = ""
        var answer: suspend () -> String? = { "{\"certain\":[0],\"uncertain\":[]}" }
        override suspend fun boundedCompletion(userMessage: String, systemPrompt: String): String? {
            calls++
            payload = userMessage
            assertEquals(MemoryEvidencePrompt.TEXT, systemPrompt)
            return answer()
        }
        override suspend fun chatSync(userMessage: String, systemPrompt: String?): String = error("unbounded")
        override fun chatStream(messages: List<ChatMessage>, systemPrompt: String?,
            functions: List<FunctionDefinition>, prefixVersion: String?): Flow<StreamChunk> = emptyFlow()
    }
    private val source = MessageEntity("s", "c", "user", "Only after confirmation.", source = "CHAT_WINDOW")
    private val memory = MemoryEntity("m", source.content.orEmpty(), origin = "USER_QUOTE",
        sourceMessageId = "s", sourceConversationId = "c")
    private var snapshot: MemoryEvidenceSnapshot? = MemoryEvidenceSnapshot(memory, source, false, false)
    private var time = 100L
    private val model = Model()
    private val reader = MemoryEvidenceReader { if (it == "m") snapshot else null }
    private val pipeline = MemoryEvidencePipeline(reader, { time }, 2500)

    @Test fun onlyTextIsSentAndOriginalSlotsSurviveExcludedSources() = runBlocking {
        val result = pipeline.select("When?", listOf("missing", "m"), model)
        assertTrue(result.completed)
        assertEquals(listOf(EvidenceSupport.Use.NONE, EvidenceSupport.Use.CERTAIN), result.entries.map { it.use })
        val item = JSONObject(model.payload).getJSONArray("candidates").getJSONObject(0)
        assertEquals(setOf("slot", "text"), item.keys().asSequence().toSet())
        assertEquals(source.content, item.getString("text"))
        assertEquals(source.content, result.entries.last().text)
        assertEquals("USER_QUOTE", result.entries.last().evidenceKind)
    }

    @Test fun deletionDuringModelCallNeverReturnsSupport() = runBlocking {
        model.answer = { snapshot = null; "{\"certain\":[0],\"uncertain\":[]}" }
        val result = pipeline.select("When?", listOf("m"), model)
        assertEquals(EvidenceSupport.Use.NONE, result.entries.single().use)
    }

    @Test fun currentTimeAndConflictAreReadAfterInference() = runBlocking {
        snapshot = snapshot?.copy(memory = memory.copy(validUntil = 101))
        model.answer = { time = 101; "{\"certain\":[0],\"uncertain\":[]}" }
        assertEquals(EvidenceSupport.Use.NONE, pipeline.select("When?", listOf("m"), model).entries.single().use)
        snapshot = snapshot?.copy(pendingConflict = true)
        assertEquals(EvidenceSupport.Use.UNCERTAIN,
            pipeline.select("Historically?", listOf("m"), model, history = true).entries.single().use)
    }

    @Test fun malformedNullFailureAndModelChangeAreNotSuccessfulAbstention() = runBlocking {
        for (answer in listOf(null, "{}", "{\"certain\":[0],\"uncertain\":[0]}")) {
            model.answer = { answer }
            val result = pipeline.select("When?", listOf("m"), model)
            assertFalse(result.completed)
            assertTrue(result.entries.isEmpty())
        }
        model.answer = { error("synthetic failure") }
        assertEquals("unavailable", pipeline.select("When?", listOf("m"), model).status)
        model.answer = { model.retrievalIdentity = "changed"; "{\"certain\":[],\"uncertain\":[]}" }
        assertEquals("model_changed", pipeline.select("When?", listOf("m"), model).status)
    }

    @Test fun successfulAbstentionIsDistinctAndNeverCached() = runBlocking {
        model.answer = { "{\"certain\":[],\"uncertain\":[]}" }
        repeat(2) { assertTrue(pipeline.select("When?", listOf("m"), model).completed) }
        assertEquals(2, model.calls)
    }

    @Test fun deadlineIncludesSourceReadsAndModelAndRevalidation() = runBlocking {
        val slowReader = MemoryEvidenceReader { delay(50); snapshot }
        assertEquals("timeout", MemoryEvidencePipeline(slowReader, { time }, 10)
            .select("When?", listOf("m"), model).status)
        assertEquals(0, model.calls)
        model.answer = { delay(50); "{\"certain\":[0],\"uncertain\":[]}" }
        assertEquals("timeout", MemoryEvidencePipeline(reader, { time }, 10)
            .select("When?", listOf("m"), model).status)
        var reads = 0
        val delayedRecheck = MemoryEvidenceReader {
            reads++
            if (reads > 1) delay(50)
            snapshot
        }
        model.answer = { "{\"certain\":[0],\"uncertain\":[]}" }
        assertEquals("timeout", MemoryEvidencePipeline(delayedRecheck, { time }, 10)
            .select("When?", listOf("m"), model).status)
    }

    @Test fun concurrentRequestIsBusyAndCancellationReleasesTheLock() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        model.answer = { entered.complete(Unit); CompletableDeferred<String>().await() }
        val running = async { pipeline.select("When?", listOf("m"), model) }
        entered.await()
        assertEquals("busy", pipeline.select("When?", listOf("m"), model).status)
        running.cancelAndJoin()
        model.answer = { "{\"certain\":[],\"uncertain\":[]}" }
        assertTrue(pipeline.select("When?", listOf("m"), model).completed)
    }

    @Test fun invalidAndUnverifiableInputNeverCallsModel() = runBlocking {
        assertEquals("invalid_input", pipeline.select(" ", listOf("m"), model).status)
        assertEquals("invalid_input", pipeline.select("q", List(9) { "$it" }, model).status)
        assertEquals("invalid_input", pipeline.select("q", listOf("m", "m"), model).status)
        assertEquals("no_verified_sources", pipeline.select("q", listOf("missing"), model).status)
        assertEquals(0, model.calls)
    }

    @Test fun promptMatchesFrozenIndependentGateExactly() {
        assertEquals("3980ba2fd02cd9acd10edbdb850c3eee12bb1d330ffc09e1105b537f53b02c5c",
            EvidenceSupport.digest(MemoryEvidencePrompt.TEXT))
    }

    @Test fun scopedMemoryCannotBecomeAnUnqualifiedPersonalRule() = runBlocking {
        snapshot = snapshot?.copy(memory = memory.copy(scope = "work"))
        assertEquals("no_verified_sources", pipeline.select("When?", listOf("m"), model).status)
        assertEquals(0, model.calls)
        val explicit = pipeline.select("When?", listOf("m"), model, scope = "work")
        assertTrue(explicit.completed)
        assertEquals("work", explicit.entries.single().scope)
    }

    @Test fun blockingLateCompletionIsDiscardedEvenWhenTheTimerCannotRun() = runBlocking {
        model.answer = { Thread.sleep(40); "{\"certain\":[0],\"uncertain\":[]}" }
        val result = MemoryEvidencePipeline(reader, { time }, 10).select("When?", listOf("m"), model)
        assertEquals("timeout", result.status)
        assertTrue(result.entries.isEmpty())
    }

    @Test fun modelChangeWhileReadingSourcesPreventsTheRequestItself() = runBlocking {
        val changing = MemoryEvidenceReader { model.retrievalIdentity = "new-configuration"; snapshot }
        val result = MemoryEvidencePipeline(changing, { time }, 2500).select("When?", listOf("m"), model)
        assertEquals("model_changed", result.status)
        assertEquals(0, model.calls)
    }

    @Test fun insufficientContextIsRejectedWithoutCallingTheProvider() = runBlocking {
        model.contextWindow = 4096
        assertEquals("model_unavailable", pipeline.select("When?", listOf("m"), model).status)
        assertEquals(0, model.calls)
    }
}
