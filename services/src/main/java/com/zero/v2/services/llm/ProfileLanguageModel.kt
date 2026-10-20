package com.zero.v2.services.llm

import com.zero.v2.core.common.ChatMessage
import com.zero.v2.core.common.FunctionDefinition
import com.zero.v2.core.common.StreamChunk
import kotlinx.coroutines.flow.Flow

/**
 * Profile 驱动的 ILanguageModel 实现 — 开发前规范化 N2.6a。
 *
 * 一个 LlmProfile = 一个可被 ModelSelector 选择的模型；chat 委托 OpenAiCompatibleClient，
 * 参数差异经 ModelContract。回合开始前读取配置，snapshotForTurn 冻结本轮配置与凭据。
 * 新增模型 = LlmProfileStore 加 profile → 本类零改动自动注册。
 */
class ProfileLanguageModel private constructor(
    override val profileId: String,
    private val client: OpenAiCompatibleClient,
    private val currentProfile: () -> LlmProfile,
    private val credential: (LlmProfile) -> String?,
    private val frozen: Boolean,
) : ILanguageModel {

    constructor(profileId: String, fallback: LlmProfile, client: OpenAiCompatibleClient,
        profileStore: LlmProfileStore) : this(profileId, client,
        { profileStore.get(profileId) ?: fallback }, client::resolveApiKey, false)

    private fun profile(): LlmProfile = currentProfile()

    override fun snapshotForTurn(): ILanguageModel {
        if (frozen) return this
        val captured = LlmTurnSnapshot(profile(), credential)
        return ProfileLanguageModel(profileId, client, { captured.profile }, { captured.credential }, true)
    }

    override val modelId: String get() = profile().modelId
    override val displayName: String get() = profile().displayName
    override val contextWindow: Int get() = profile().contextWindow
    override val outputReserve: Int get() = profile().maxTokens

    /** M2：能力来自 profile 显式声明（不推断 modelId）。 */
    override val capabilities: Set<ModelCapability> get() = profile().capabilities

    /** M2：Key 是否已配置（缺 Key 不入候选）。 */
    override val keyConfigured: Boolean get() = profile().let {
        CloudflareChatTransport.credentialReady(it, credential(it))
    }

    /** M2：用户优先级（tie-break）。 */
    override val priority: Int get() = profile().priority

    /** M2：profile.enabled（禁用不入候选）。 */
    override val enabled: Boolean get() = profile().enabled

    override val retrievalIdentity: String get() = profile().let {
        "${it.baseUrl.trimEnd('/')}|${it.modelId}|bounded-chat:v1"
    }

    override suspend fun boundedCompletion(userMessage: String, systemPrompt: String): String? =
        profile().let { client.boundedCompletion(it, userMessage, systemPrompt, credential(it).orEmpty()) }

    override fun estimateInputTokens(messages: List<ChatMessage>, systemPrompt: String?,
        functions: List<FunctionDefinition>): Int = profile().let {
        client.requestContext.estimate(it, messages, systemPrompt, functions, credential(it))
    }

    override fun inputBudgetDiagnostic(messages: List<ChatMessage>, systemPrompt: String?,
        functions: List<FunctionDefinition>): String = profile().let {
        client.requestContext.estimateDiagnostic(it, messages, systemPrompt, functions, credential(it))
    }

    override fun noteContextEvent(messages: List<ChatMessage>, event: String) {
        profile().let { client.requestContext.noteEvent(it, credential(it), messages, event) }
    }

    override fun chatStream(
        messages: List<ChatMessage>,
        systemPrompt: String?,
        functions: List<FunctionDefinition>,
        prefixVersion: String?,
    ): Flow<StreamChunk> =
        profile().let {
            client.chatStream(it, messages, systemPrompt, functions, credential(it).orEmpty(), prefixVersion)
        }

    override suspend fun chatSync(userMessage: String, systemPrompt: String?): String =
        profile().let { client.chatSync(it, userMessage, systemPrompt, credential(it).orEmpty()) }
}
