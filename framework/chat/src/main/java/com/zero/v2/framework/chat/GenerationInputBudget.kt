package com.zero.v2.framework.chat

import com.zero.v2.core.common.ChatMessage
import com.zero.v2.core.common.FunctionDefinition
import com.zero.v2.core.common.PromptPrefix
import com.zero.v2.services.llm.ILanguageModel
import java.io.IOException

/** Shared by generation selection and the post-slice check, without changing the transcript. */
internal class GenerationInputBudget(
    private val allocator: TokenBudgetAllocator,
    private val model: ILanguageModel,
    private val system: String,
    private val tools: List<FunctionDefinition>,
) {
    private val overhead = model.estimateInputTokens(emptyList(), system, tools)
        ?: (allocator.estimateTokens(system) + allocator.estimateTokens(PromptPrefix.toolManifest(tools)) + FRAMING)

    fun historyEstimate(messages: List<ChatMessage>): Int = (inputEstimate(messages) - overhead).coerceAtLeast(0)

    fun historyLimit(configured: Int): Int = minOf(configured.coerceAtLeast(0),
        (allocator.allocateFull(model.contextWindow, model.outputReserve).availableInput - overhead).coerceAtLeast(0))

    fun check(messages: List<ChatMessage>) {
        val input = inputEstimate(messages)
        if (input > allocator.allocateFull(model.contextWindow, model.outputReserve).availableInput) {
            throw IOException("REQUEST_CONTEXT_LIMIT: inputEstimate=$input output=${model.outputReserve} limit=${model.contextWindow}")
        }
    }

    private fun inputEstimate(messages: List<ChatMessage>): Int = model.estimateInputTokens(messages, system, tools)
        ?: (allocator.estimateMessageTokens(messages).toLong() + overhead).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()

    private companion object { const val FRAMING = 256 }
}
