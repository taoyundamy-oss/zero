package com.zero.v2.services.embedding

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Bounded, process-local query vectors shared by both domains; never caches retrieval results. */
internal class QueryEmbeddingCache(private val provider: EmbeddingProvider) {
    private data class Entry(val vector: FloatArray, val expires: Long)
    private val lock = Mutex()
    private val entries = LinkedHashMap<Pair<String, String>, Entry>()

    suspend fun get(query: String): FloatArray? = lock.withLock {
        if (query.isBlank() || query.length > MAX_QUERY || !provider.isAvailable()) return@withLock null
        val backend = provider.identity
        val key = backend to query
        val now = System.nanoTime()
        entries.entries.removeAll { it.value.expires <= now }
        entries[key]?.let { return@withLock it.vector.copyOf() }
        val vector = provider.embedQuery(query) ?: return@withLock null
        if (provider.identity != backend) return@withLock null
        if (vector.size !in 1..MAX_DIMENSIONS || vector.any { !it.isFinite() } ||
            vector.none { it != 0f }) return@withLock null
        entries[key] = Entry(vector.copyOf(), now + TTL_NS)
        if (entries.size > MAX_ENTRIES) entries.remove(entries.keys.first())
        vector.copyOf()
    }

    private companion object {
        const val MAX_QUERY = 2048
        const val MAX_DIMENSIONS = 16384
        const val MAX_ENTRIES = 64
        const val TTL_NS = 300_000_000_000L
    }
}
