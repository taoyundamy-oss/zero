package com.zero.v2.app.acceptance

import com.zero.v2.services.embedding.EmbeddingProvider
import com.zero.v2.services.embedding.EmbeddingPurpose
import com.zero.v2.services.llm.LlmProfile
import com.zero.v2.services.llm.LlmProfileStore
import com.zero.v2.services.llm.OpenAiCompatibleClient
import javax.inject.Inject

/** Debug-only, fixed alternative model. Never updates user profiles or exports credentials. */
class EmbeddingCandidateFactory @Inject constructor(
    private val profiles: LlmProfileStore,
    private val client: OpenAiCompatibleClient,
) {
    fun create(model: String): EmbeddingProvider {
        require(model in setOf("text-embedding-v3", "text-embedding-v4"))
        val selected = checkNotNull(profiles.getEnabled(LlmProfile.Kind.EMBEDDING).firstOrNull())
        val profile = selected.copy(modelId = model)
        return object : EmbeddingProvider {
            override val modelId = model
            override val identity = "candidate:${profile.baseUrl.trimEnd('/')}|$model|query-document:v1"
            override suspend fun isAvailable() = client.resolveApiKey(profile) != null
            override suspend fun embed(text: String) = embedBatch(listOf(text)).firstOrNull()
            override suspend fun embedQuery(text: String) = embedBatch(listOf(text), EmbeddingPurpose.QUERY).firstOrNull()
            override suspend fun embedBatch(texts: List<String>) = embedBatch(texts, EmbeddingPurpose.DOCUMENT)
            override suspend fun embedBatch(texts: List<String>, purpose: EmbeddingPurpose) =
                texts.chunked(10).flatMap { client.embeddings(profile, it, purpose) }
        }
    }
}
