package com.zero.v2.app.knowledge

import com.zero.v2.core.common.ChatMessage
import com.zero.v2.core.common.FunctionDefinition
import com.zero.v2.core.common.StreamChunk
import com.zero.v2.core.data.entity.KbChunkEntity
import com.zero.v2.core.data.entity.KbDocEntity
import com.zero.v2.services.llm.ILanguageModel
import com.zero.v2.services.llm.ModelCapability
import com.zero.v2.services.llm.ModelSelector
import com.zero.v2.services.retrieval.BoundedSemanticReranker
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class KnowledgeSearchContractTest {
    @Test fun pathAndTagScopeApplyBeforeCandidateTruncation() = runBlocking {
        val dao = KnowledgeIndexTest.IndexDao()
        repeat(320) { index ->
            val doc = KbDocEntity("outside$index", "outside/$index.md", "范围外", 0, 0, 1, 0)
            dao.insertDoc(doc)
            dao.insertChunks(listOf(KbChunkEntity("outside$index", doc.id, 0,
                text = "培训无关", charLen = 4, updatedAt = 0)))
        }
        val target = KbDocEntity("target", "allowed/target.md", "目标", 0, 0, 1, 0, tags = "other, chosen ")
        dao.insertDoc(target)
        dao.insertChunks(listOf(KbChunkEntity("target", target.id, 0,
            text = "培训目标", charLen = 4, updatedAt = 0)))
        val module = KnowledgeIndexTest().module(KnowledgeIndexTest.Source(), dao)
        try {
            val search = module.contributions().first { it.definition.name == "kb_search" }
            listOf(mapOf("path_prefix" to "allowed/"), mapOf("tag" to "chosen")).forEach { scope ->
                val result = search.handler(mapOf("query" to "培训", "top_k" to 1) + scope)
                assertTrue(result.data, result.data.contains("培训目标"))
                assertTrue(result.data, !result.data.contains("培训无关"))
            }
            module.config.hybridEmbedding = true
            val hybrid = search.handler(mapOf("query" to "培训", "top_k" to 1, "tag" to "chosen"))
            assertTrue(hybrid.data, hybrid.data.contains("培训目标"))
            assertEquals(listOf(target.id), dao.searchDocuments)
        } finally { module.onDestroy() }
    }

    private class Model : ILanguageModel {
        override val modelId = "fixture"
        override val displayName = modelId
        override val contextWindow = 8192
        override val capabilities = setOf(ModelCapability.CHAT)
        override val keyConfigured = true
        override val priority = 1
        override val enabled = true
        var calls = 0
        override suspend fun boundedCompletion(userMessage: String, systemPrompt: String): String {
            calls++
            return "[1]"
        }
        override suspend fun chatSync(userMessage: String, systemPrompt: String?): String = error("unbounded")
        override fun chatStream(messages: List<ChatMessage>, systemPrompt: String?,
            functions: List<FunctionDefinition>, prefixVersion: String?) = emptyFlow<StreamChunk>()
    }

    @Test fun localOnlyToolSearchNeverCallsCloudReranker() = runBlocking {
        val model = Model()
        val ranker = BoundedSemanticReranker(ModelSelector().also { it.register(model) })
        val dao = KnowledgeIndexTest.IndexDao()
        val doc = KbDocEntity("doc", "fixture.md", "资料", 0, 0, 2, 0)
        dao.insertDoc(doc)
        dao.insertChunks((0..1).map { KbChunkEntity("c$it", doc.id, it,
            text = "培训地点$it", charLen = 5, updatedAt = 0) })
        val module = KnowledgeIndexTest().module(KnowledgeIndexTest.Source(), dao, reranker = ranker)
        try {
            module.config.hybridEmbedding = false
            val search = module.contributions().first { it.definition.name == "kb_search" }
            val result = search.handler(mapOf("query" to "培训", "top_k" to 1))
            assertTrue(result.success)
            assertEquals("local-only mode must not spend tokens", 0, model.calls)
            assertTrue(result.data.contains("培训地点0"))
            module.config.hybridEmbedding = true
            val cloudResult = search.handler(mapOf("query" to "培训", "top_k" to 1))
            assertEquals(1, model.calls)
            assertTrue(cloudResult.data.contains("培训地点1"))
            module.config.hybridEmbedding = false
            val localAgain = search.handler(mapOf("query" to "地点", "top_k" to 1))
            assertEquals("disabling must apply to new queries too", 1, model.calls)
            assertTrue(localAgain.data.contains("培训地点0"))
        } finally { module.onDestroy() }
    }
}
