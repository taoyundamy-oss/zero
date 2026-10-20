package com.zero.v2.services.llm

import com.zero.v2.core.common.ChatMessage
import com.zero.v2.core.common.FunctionDefinition
import com.zero.v2.core.common.MessageRole
import com.zero.v2.core.common.StreamChunk
import com.zero.v2.services.chatgpt.ChatGptAuth
import com.zero.v2.services.chatgpt.ChatGptLease
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/** One explicit account/model per Agent turn, with renewable credentials and no paid-route fallback. */
class ChatGptPlanLanguageModel private constructor(
    private val current: () -> LlmProfile,
    private val client: OpenAiCompatibleClient,
    private val auth: ChatGptAuth,
    private val lease: ChatGptLease?,
) : ILanguageModel {
    constructor(profile: LlmProfile, client: OpenAiCompatibleClient, auth: ChatGptAuth,
        store: LlmProfileStore) : this({ store.get(profile.id) ?: profile }, client, auth, null)

    override val modelId get() = current().modelId
    override val profileId get() = current().id
    override val displayName get() = current().displayName
    override val contextWindow get() = current().contextWindow
    override val outputReserve get() = current().maxTokens
    override val capabilities get() = current().capabilities
    override val enabled get() = current().enabled
    override val priority get() = current().priority
    // Explicitly enabled account routes fail at authorization instead of silently selecting a paid peer.
    override val keyConfigured get() = true
    override val retrievalIdentity get() = ProviderToolState.scope(current())
    override fun snapshotForTurn(): ILanguageModel {
        if (lease != null) return this
        val frozen = current()
        return ChatGptPlanLanguageModel({ frozen }, client, auth, auth.lease(frozen.connectionId))
    }
    override fun estimateInputTokens(messages: List<ChatMessage>, systemPrompt: String?,
        functions: List<FunctionDefinition>): Int = current().let {
        client.requestContext.estimate(it, messages, systemPrompt, functions, it.connectionId)
    }
    override fun inputBudgetDiagnostic(messages: List<ChatMessage>, systemPrompt: String?,
        functions: List<FunctionDefinition>): String = current().let {
        client.requestContext.estimateDiagnostic(it, messages, systemPrompt, functions, it.connectionId)
    }
    override fun noteContextEvent(messages: List<ChatMessage>, event: String) {
        current().let { client.requestContext.noteEvent(it, it.connectionId, messages, event) }
    }
    override fun chatStream(messages: List<ChatMessage>, systemPrompt: String?,
        functions: List<FunctionDefinition>, prefixVersion: String?): Flow<StreamChunk> = flow {
        val profile = current()
        val access = lease ?: auth.lease(profile.connectionId)
        client.chatStream(profile, messages, systemPrompt, functions, prefixVersion = prefixVersion,
            planLease = access).collect { emit(it) }
    }
    override suspend fun chatSync(userMessage: String, systemPrompt: String?): String = buildString {
        chatStream(listOf(ChatMessage(role = MessageRole.USER, content = userMessage)), systemPrompt,
            emptyList(), null).collect { it.contentDelta?.let(::append) }
    }
    // This route has no server-side output cap; bounded retrieval uses its existing local fallback.
}
