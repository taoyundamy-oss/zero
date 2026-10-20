package com.zero.v2.services.embedding

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class QueryEmbeddingCacheTest {
    private class Provider : EmbeddingProvider {
        override val modelId = "fixture"
        override var identity = "first"
        var calls = 0
        var available = true
        var response = floatArrayOf(1f, 2f)
        override suspend fun isAvailable() = available
        override suspend fun embed(text: String): FloatArray {
            calls++
            delay(10)
            return response
        }
        override suspend fun embedBatch(texts: List<String>) = texts.map { embed(it) }
    }

    @Test fun concurrentDomainsAndToolsShareOneQueryButNotBackends() = runBlocking {
        val provider = Provider()
        val cache = QueryEmbeddingCache(provider)
        val vectors = (1..4).map { async { cache.get("地点")!! } }.awaitAll()
        assertEquals(1, provider.calls)
        vectors.first()[0] = 99f
        assertEquals(1f, cache.get("地点")!![0], 0f)
        provider.identity = "second"
        cache.get("地点")
        assertEquals(2, provider.calls)
        provider.available = false
        assertNull(cache.get("地点"))
    }

    @Test fun malformedVectorsAndFailedResponsesAreNeverReused() = runBlocking {
        val provider = Provider()
        val cache = QueryEmbeddingCache(provider)
        provider.response = floatArrayOf(Float.NaN)
        assertNull(cache.get("地点"))
        provider.response = floatArrayOf(0f)
        assertNull(cache.get("地点"))
        provider.response = floatArrayOf(1f)
        assertEquals(1f, cache.get("地点")!![0], 0f)
        assertEquals(3, provider.calls)
    }
}
