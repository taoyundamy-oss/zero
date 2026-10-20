package com.zero.v2.framework.chat

import com.zero.v2.core.common.ChatMessage
import com.zero.v2.core.common.FunctionDefinition
import com.zero.v2.core.common.MessageRole
import com.zero.v2.core.common.StreamChunk
import com.zero.v2.core.common.ToolCall
import com.zero.v2.services.llm.ILanguageModel
import com.zero.v2.services.llm.ModelCapability
import kotlinx.coroutines.flow.Flow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GenerationInputBudgetTest {
    private class Model(private val measured: Int? = null, override val contextWindow: Int = 24_576,
        private val overhead: Int = 100) : ILanguageModel {
        override val modelId = "test"
        override val displayName = "test"
        override val capabilities = setOf(ModelCapability.CHAT)
        override val keyConfigured = true
        override val priority = 1
        override val enabled = true
        override val outputReserve = 2048
        override fun estimateInputTokens(messages: List<ChatMessage>, systemPrompt: String?,
            functions: List<FunctionDefinition>): Int? = measured?.let { if (messages.isEmpty()) overhead else it }
        override fun chatStream(messages: List<ChatMessage>, systemPrompt: String?, functions: List<FunctionDefinition>,
            prefixVersion: String?): Flow<StreamChunk> = error("No model requests")
        override suspend fun chatSync(userMessage: String, systemPrompt: String?): String = error("No requests")
    }

    @Test fun `generation decision uses whole request observation rather than opaque history bytes`() {
        val messages = listOf(ChatMessage(role = MessageRole.USER, content = "x".repeat(500_000)))
        val budget = GenerationInputBudget(TokenBudgetAllocator(), Model(90_000, 1_048_576), "system", emptyList())
        assertTrue(budget.historyEstimate(messages) < 256_000 * 0.9)
        budget.check(messages)
        assertEquals(500_000, messages.single().content!!.length)
    }

    @Test fun `encoded overhead clamps history before an oversized request reaches final guard`() {
        val messages = listOf(ChatMessage(role = MessageRole.USER, content = "history"))
        val budget = GenerationInputBudget(TokenBudgetAllocator(), Model(22_000, overhead = 10_000),
            "system", emptyList())
        assertTrue(budget.historyEstimate(messages) > budget.historyLimit(256_000) * 0.9)
        assertEquals(11_300, budget.historyLimit(256_000))
        assertEquals(5_000, budget.historyLimit(5_000))
    }

    @Test fun `candidate requests determine retained suffix without modifying original history`() {
        val messages = (0..9).map { ChatMessage(role = MessageRole.USER, content = "opaque".repeat(10000)) }
        val visited = mutableListOf<Int>()
        val result = ContextWindow.sliceByRequestBudget(messages, 35) { candidate ->
            visited += candidate.size
            candidate.size * 10
        }
        assertEquals(messages.takeLast(3), result.messages)
        assertEquals(30, result.estTokens)
        assertTrue(visited.size <= 6)
        assertEquals(60000, messages.first().content!!.length)
    }

    @Test fun `oversized final tool group retains assistant and every result`() {
        val messages = listOf(ChatMessage(role = MessageRole.USER, content = "old"),
            ChatMessage(role = MessageRole.ASSISTANT, content = "", toolCalls = listOf(ToolCall("a", "probe", "{}"))),
            ChatMessage(role = MessageRole.TOOL, toolCallId = "a", content = "full"))
        val result = ContextWindow.sliceByRequestBudget(messages, 1) { it.size * 10 }
        assertEquals(messages.takeLast(2), result.messages)
        assertEquals(20, result.estTokens)
    }

    @Test(expected = java.io.IOException::class)
    fun `post slice guard catches retained atomic oversized tool batch`() {
        val messages = listOf(ChatMessage(role = MessageRole.ASSISTANT, content = "",
            toolCalls = listOf(ToolCall("c", "probe", "{}"))),
            ChatMessage(role = MessageRole.TOOL, toolCallId = "c", content = "中".repeat(30_000)))
        val allocator = TokenBudgetAllocator()
        val slice = ContextWindow.sliceForGeneration(messages, 15_000, { allocator.estimateMessageTokens(it) }, true, 500)
        assertEquals(messages, slice.messages)
        GenerationInputBudget(allocator, Model(), "system", emptyList()).check(slice.messages)
    }

    @Test(expected = java.io.IOException::class)
    fun `system overhead also participates in final boundary`() {
        GenerationInputBudget(TokenBudgetAllocator(), Model(), "中".repeat(30_000), emptyList()).check(emptyList())
    }
}
