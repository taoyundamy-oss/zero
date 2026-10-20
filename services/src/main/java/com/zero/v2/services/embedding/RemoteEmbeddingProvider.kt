package com.zero.v2.services.embedding

import com.zero.v2.services.llm.LlmProfile
import com.zero.v2.services.llm.LlmProfileStore
import com.zero.v2.services.llm.OpenAiCompatibleClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 远程嵌入实现 — DashScope OpenAI 兼容模式（Step 4.6-pre，D31 检索主径；N2.5 迁统一客户端）。
 *
 * POST /compatible-mode/v1/embeddings（经 OpenAiCompatibleClient，Key 链/计量/埋点统一）。
 * Key: embedding:qwen → llm_client:qwen 回退链（profile 内置，同旧语义）。
 * S1.2-b：profile 经 LlmProfileStore 即时生效（原静态 defaults——用户改 profile 不生效）；
 * 编辑入口 = 记忆模块详情页「模型管理（向量嵌入）」（P1-7：无绑定按消费方=Memory 归属）。
 * DashScope 单请求上限 10 条 — 保持分块；失败返回 null 位 — 调用方（MemoryModule）走 bigram 降级链。
 */
@Singleton
class RemoteEmbeddingProvider @Inject constructor(
    private val client: OpenAiCompatibleClient,
    private val profileStore: LlmProfileStore
) : EmbeddingProvider {
    private val credentials = EmbeddingCredentialGate()

    override val modelId: String
        get() = profile().modelId

    override val identity: String
        get() = identityOf(profile())

    private fun identityOf(profile: LlmProfile): String =
        com.zero.v2.services.llm.EmbeddingRequestSpec.identity(profile.baseUrl, profile.modelId)

    private fun profile(): LlmProfile =
        profileStore.getEnabled(LlmProfile.Kind.EMBEDDING).firstOrNull()
            ?: profileStore.byKind(LlmProfile.Kind.EMBEDDING).firstOrNull()
            ?: LlmProfile.defaults().first { it.id == "qwen-embedding" }

    override suspend fun isAvailable(): Boolean {
        val selected = profile()
        return credentials.permits(credentials.stamp(identityOf(selected), client.resolveApiKey(selected)))
    }

    override suspend fun embed(text: String): FloatArray? =
        embedBatch(listOf(text)).firstOrNull()

    override suspend fun embedQuery(text: String): FloatArray? =
        embedBatch(listOf(text), EmbeddingPurpose.QUERY).firstOrNull()

    override suspend fun embedBatch(texts: List<String>): List<FloatArray?> =
        embedBatch(texts, EmbeddingPurpose.DOCUMENT)

    override suspend fun embedBatch(
        texts: List<String>, purpose: EmbeddingPurpose,
    ): List<FloatArray?> = withContext(Dispatchers.IO) {
        if (texts.isEmpty()) return@withContext emptyList()
        // DashScope text-embedding-v3 单请求上限 10 条 — 分块请求后按序拼接
        val selected = profile()
        val expected = identityOf(selected)
        val stamp = credentials.stamp(expected, client.resolveApiKey(selected))
        if (!credentials.permits(stamp)) return@withContext texts.map { null }
        val vectors = try {
            texts.chunked(BATCH_LIMIT).flatMap { chunk -> client.embeddings(selected, chunk, purpose) }
        } catch (_: EmbeddingAuthorizationException) {
            credentials.reject(stamp)
            return@withContext texts.map { null }
        }
        if (identity != expected) return@withContext texts.map { null }
        vectors
    }

    private companion object {
        /** DashScope embedding 单请求批量上限 */
        const val BATCH_LIMIT = 10
    }
}
