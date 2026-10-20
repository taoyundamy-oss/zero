package com.zero.v2.app.acceptance

import androidx.test.platform.app.InstrumentationRegistry
import com.zero.v2.services.embedding.EmbeddingProvider
import com.zero.v2.services.embedding.EmbeddingPurpose
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import com.zero.v2.services.llm.ILanguageModel
import com.zero.v2.services.llm.ModelCapability
import com.zero.v2.core.common.ChatMessage
import com.zero.v2.core.common.FunctionDefinition
import com.zero.v2.core.common.StreamChunk
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

class AcceptanceResponseCacheTest {
    @Test fun completionReceiptsBindBackendAndBothPromptParts(): Unit = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(context.cacheDir, "a4-rank-cache-${java.util.UUID.randomUUID()}")
        var requests = 0
        var backend = "first"
        val actual = object : ILanguageModel {
            override val modelId = "fixture"
            override val displayName = "fixture"
            override val contextWindow = 8192
            override val capabilities = emptySet<ModelCapability>()
            override val keyConfigured = true
            override val priority = 0
            override val enabled = true
            override val retrievalIdentity get() = backend
            override suspend fun boundedCompletion(userMessage: String, systemPrompt: String): String {
                requests++; return "[0,1]"
            }
            override fun chatStream(messages: List<ChatMessage>, systemPrompt: String?,
                functions: List<FunctionDefinition>, prefixVersion: String?): Flow<StreamChunk> = emptyFlow()
            override suspend fun chatSync(userMessage: String, systemPrompt: String?) = error("not used")
        }
        try {
            val first = AcceptanceResponseCache(root).model(actual)
            assertEquals("[0,1]", first.boundedCompletion("query", "policy"))
            assertEquals("[0,1]", AcceptanceResponseCache(root).model(actual).boundedCompletion("query", "policy"))
            assertEquals(1, requests)
            first.boundedCompletion("different query", "policy")
            first.boundedCompletion("query", "different policy")
            backend = "changed"
            first.boundedCompletion("query", "policy")
            assertEquals(4, requests)
        } finally { root.deleteRecursively() }
    }
    @Test fun receiptsSurviveRestartAndKeepQueryAndBackendSeparate() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(context.cacheDir, "a4-cache-test-${java.util.UUID.randomUUID()}")
        var requests = 0
        var backend = "first"
        val actual = object : EmbeddingProvider {
            override val modelId = "fixture"
            override val identity get() = backend
            override suspend fun isAvailable() = true
            override suspend fun embed(text: String) = floatArrayOf(1f, .5f)
            override suspend fun embedBatch(texts: List<String>): List<FloatArray?> {
                requests++; return texts.map { floatArrayOf(1f, .5f) }
            }
        }
        try {
            AcceptanceResponseCache(root).provider(actual).embed("独立合成资料")
            val reopened = AcceptanceResponseCache(root).provider(actual)
            assertEquals(.5f, reopened.embed("独立合成资料")!![1], 0f)
            assertEquals(1, requests)
            reopened.embedBatch(listOf("独立合成资料"), EmbeddingPurpose.QUERY)
            assertEquals(2, requests)
            backend = "changed"
            reopened.embed("独立合成资料")
            assertEquals(3, requests)
            root.listFiles()!!.forEach { it.writeText("invalid cache") }
            var failed = false
            try { reopened.embed("独立合成资料") } catch (_: Exception) { failed = true }
            assertTrue("corruption must fail instead of silently charging another call", failed)
            assertEquals(3, requests)
        } finally { root.deleteRecursively() }
    }
}
