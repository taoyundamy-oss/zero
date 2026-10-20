package com.zero.v2.services.llm

import com.zero.v2.core.common.ChatMessage
import com.zero.v2.core.common.FunctionDefinition
import okhttp3.OkHttpClient
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Named

/** Shares one request representation between generation sizing and final transport validation. */
class RequestContextPreparation @Inject constructor(
    private val attachments: StoredAttachmentReader,
    private val budget: RequestContextBudget,
    @Named("llm") client: OkHttpClient,
    private val diagnostics: CacheRequestDiagnostics,
    internal val usageLedger: UsageLedger? = null,
) {
    constructor(attachments: StoredAttachmentReader, budget: RequestContextBudget, client: OkHttpClient) :
        this(attachments, budget, client, CacheRequestDiagnostics())
    private val codec = OpenAiCompatibleCodec()
    private val local = LocalLlamaContextGuard(client)

    companion object {
        private const val MAX_PENDING_EVENTS = 32
        fun validateEvent(event: String) {
            require(event in setOf("generation_switch", "scope_changed", "anchor_missing",
                "memory_generation", "memory_withdrawn", "memory_anchor_missing", "memory_budget"))
        }
    }

    private val events = linkedMapOf<String, String>()

    @Synchronized
    fun noteEvent(profile: LlmProfile, key: String?, messages: List<ChatMessage>, event: String) {
        validateEvent(event)
        events[scope(profile, key, messages)] = event
        while (events.size > MAX_PENDING_EVENTS) events.remove(events.keys.first())
    }

    @Synchronized
    fun takeEvent(profile: LlmProfile, key: String?, messages: List<ChatMessage>): String? =
        events.remove(scope(profile, key, messages))

    fun describe(profile: LlmProfile, body: JSONObject, key: String?, messages: List<ChatMessage>): String =
        diagnostics.describe(scope(profile, key, messages), body, messages)

    fun budgetDiagnostic(profile: LlmProfile, body: JSONObject, key: String?, messages: List<ChatMessage>): String =
        budget.inspect(scope(profile, key, messages), body).diagnostic()

    fun restore(profile: LlmProfile, messages: List<ChatMessage>): List<ChatMessage> =
        if (codec.supportsImageInput(profile)) attachments.restore(messages) else messages

    fun estimate(profile: LlmProfile, messages: List<ChatMessage>, system: String?,
        functions: List<FunctionDefinition>, key: String?): Int =
        budget.estimate(scope(profile, key, messages),
            ChatRequestBody.build(profile, system, restore(profile, messages), true, functions))

    fun estimateDiagnostic(profile: LlmProfile, messages: List<ChatMessage>, system: String?,
        functions: List<FunctionDefinition>, key: String?): String =
        budget.inspect(scope(profile, key, messages),
            ChatRequestBody.build(profile, system, restore(profile, messages), true, functions)).diagnostic()

    fun prepare(profile: LlmProfile, body: JSONObject, key: String?, messages: List<ChatMessage>) {
        local.prepare(profile, body, key)
        // Exact llama.cpp tokenization takes precedence over the cloud fallback estimate.
        if (!profile.localLlamaPreflight) budget.requireFits(scope(profile, key, messages),
            body, profile.contextWindow, profile.maxTokens)
    }

    fun observe(profile: LlmProfile, body: JSONObject, key: String?, messages: List<ChatMessage>, input: Long) {
        budget.observe(scope(profile, key, messages), body, input)
    }

    private fun scope(profile: LlmProfile, key: String?, messages: List<ChatMessage>): String =
        RequestContextBudget.digest(ProviderToolState.scope(profile) + ":" +
            RequestContextBudget.digest(ChatGptPlanProtocol.cacheCredential(profile, key)) + ":" +
            messages.firstOrNull()?.id.orEmpty())
}
