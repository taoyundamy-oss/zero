package com.zero.v2.app.acceptance

import androidx.test.platform.app.InstrumentationRegistry
import com.zero.v2.core.common.*
import com.zero.v2.framework.chat.ChatBotModule
import com.zero.v2.services.llm.*
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.Assert.*
import org.junit.Test

/** Real engine and turn gate, with a sole deterministic local model and no provider registration. */
class RegenerationCancellationAcceptanceTest {
    @Test fun cancelledEmptyRetryRetainsOriginalWhilePartialRetryAndNextTurnRemainUsable() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        assertTrue(instrumentation is ProviderAcceptanceRunner)
        val entry = EntryPointAccessors.fromApplication(instrumentation.targetContext, RegenerationAcceptanceEntryPoint::class.java)
        val selector = entry.modelSelector()
        assertTrue("Requires skipped normal application startup", selector.getAll().isEmpty())
        val model = LocalModel()
        selector.register(model)
        val engine = entry.chatBot()
        val user = ChatMessage("regeneration-user", MessageRole.USER, "isolated input")
        val original = ChatMessage("regeneration-reply", MessageRole.ASSISTANT, "original reply",
            source = MessageSource.POKE, systemSource = "isolated-source", triggerId = "isolated-trigger")
        engine.initialize("isolated regeneration acceptance")
        try {
            engine.loadHistory("isolated-regeneration", listOf(user, original))
            val noOutput = launch(start = CoroutineStart.UNDISPATCHED) { engine.regenerate().collect { engine.applyStreamChunk(it) } }
            model.started.await(); noOutput.cancelAndJoin()
            assertEquals(original, engine.chatHistory.value.messages.last())
            model.started = CompletableDeferred(); model.output = "partial replacement"
            val partial = launch(start = CoroutineStart.UNDISPATCHED) { engine.regenerate().collect { engine.applyStreamChunk(it) } }
            model.started.await(); partial.cancelAndJoin()
            assertEquals(original.id, engine.chatHistory.value.messages.last().id)
            assertEquals("partial replacement", engine.chatHistory.value.messages.last().content)
            assertEquals(original.source, engine.chatHistory.value.messages.last().source)
            assertEquals(original.systemSource, engine.chatHistory.value.messages.last().systemSource)
            assertEquals(original.triggerId, engine.chatHistory.value.messages.last().triggerId)
            model.wait = false; model.output = "completed replacement"
            withTimeout(3_000) { engine.regenerate().collect { engine.applyStreamChunk(it) } }
            assertEquals("completed replacement", engine.chatHistory.value.messages.last().content)
            assertEquals(original.id, engine.chatHistory.value.messages.last().id)
            assertEquals(ChatState.IDLE, engine.chatState.value)
        } finally { selector.unregister(model.modelId) }
    }
    private class LocalModel : ILanguageModel {
        override val modelId = "isolated-regeneration-model"
        override val displayName = "isolated"
        override val contextWindow = 960000
        override val capabilities = setOf(ModelCapability.CHAT, ModelCapability.FUNCTION_CALLING)
        override val keyConfigured = true
        override val priority = 0
        override val enabled = true
        var started = CompletableDeferred<Unit>()
        var output: String? = null
        var wait = true
        override suspend fun chatSync(userMessage: String, systemPrompt: String?) = error("No sync requests allowed")
        override fun chatStream(messages: List<ChatMessage>, systemPrompt: String?, functions: List<FunctionDefinition>, prefixVersion: String?): Flow<StreamChunk> = flow {
            output?.let { emit(StreamChunk(contentDelta = it)) }
            started.complete(Unit)
            if (wait) awaitCancellation() else emit(StreamChunk(isFinal = true))
        }
    }
}
