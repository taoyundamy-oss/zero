package com.zero.v2.services.llm

import kotlinx.coroutines.ensureActive

/** A single pre-output retry only: never restart tool execution or change reasoning effort. */
internal object ResponsesRecovery {
    fun stream(
        profile: LlmProfile,
        onRetry: suspend (Int) -> Unit,
        attempt: (LlmProfile) -> kotlinx.coroutines.flow.Flow<com.zero.v2.core.common.StreamChunk>,
    ): kotlinx.coroutines.flow.Flow<com.zero.v2.core.common.StreamChunk> = kotlinx.coroutines.flow.flow {
        var emittedOutput = false
        try {
            attempt(profile).collect { chunk ->
                val hasText = !chunk.contentDelta.isNullOrEmpty() || !chunk.thinkingDelta.isNullOrEmpty()
                if (hasText || chunk.toolCallDelta != null || chunk.providerContent != null) emittedOutput = true
                emit(chunk)
            }
        } catch (failure: ResponsesFailure) {
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            val next = retryBudget(profile, failure, emittedOutput) ?: throw failure
            onRetry(next)
            attempt(profile.copy(maxTokens = next)).collect { emit(it) }
        }
    }

    private const val RETRY_OUTPUT_CEILING = 16384
    private const val SAFETY_DIVISOR = 20
    fun retryBudget(profile: LlmProfile, failure: ResponsesFailure, emittedOutput: Boolean): Int? {
        val supportsBudget = ResponsesProtocol.enabled(profile) && !ChatGptPlanProtocol.enabled(profile)
        if (!supportsBudget || !failure.outputBudgetExhausted || emittedOutput) return null
        val input = failure.usage?.inputTokens ?: 0
        val next = minOf(profile.maxTokens.toLong() * 2, profile.maxOutputTokens.toLong(),
            RETRY_OUTPUT_CEILING.toLong()).toInt()
        val fits = input > 0 && input + next + profile.contextWindow / SAFETY_DIVISOR < profile.contextWindow
        return next.takeIf { it > profile.maxTokens && fits }
    }
}
