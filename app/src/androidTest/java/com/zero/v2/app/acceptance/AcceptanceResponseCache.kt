package com.zero.v2.app.acceptance

import android.util.AtomicFile
import com.zero.v2.services.embedding.EmbeddingProvider
import com.zero.v2.services.embedding.EmbeddingPurpose
import com.zero.v2.services.llm.ILanguageModel
import com.zero.v2.services.retrieval.EvidencePolicy
import java.io.File
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject

/** Synthetic acceptance only. Reuse actual receipts by exact request/backend; never invent responses. */
internal class AcceptanceResponseCache(private val root: File) {
    private val lock = Mutex()
    var hits = 0; private set
    var misses = 0; private set
    private val completions = mutableListOf<JSONObject>()
    fun completionObservations(): JSONArray = synchronized(completions) {
        JSONArray(completions.map { JSONObject(it.toString()) })
    }
    private fun observe(payload: String, prompt: String, raw: String?, cached: Boolean) = synchronized(completions) {
        completions.add(JSONObject().put("payload", payload).put("promptDigest", EvidencePolicy.bodyDigest(prompt))
            .put("response", raw ?: JSONObject.NULL).put("cached", cached))
        if (completions.size > 128) completions.removeAt(0)
    }
    private fun key(vararg parts: String) = EvidencePolicy.bodyDigest(JSONArray(parts.toList()).toString())
    private fun read(key: String): JSONObject? {
        val file = File(root, "$key.json")
        if (!file.exists()) return null
        val value = JSONObject(AtomicFile(file).readFully().toString(Charsets.UTF_8))
        check(value.getInt("schema") == 1 && value.getString("key") == key) { "acceptance_cache_corrupt" }
        return value
    }
    private fun write(key: String, value: JSONObject) {
        check(root.isDirectory || root.mkdirs())
        value.put("schema", 1).put("key", key).put("receivedAt", System.currentTimeMillis())
        val atomic = AtomicFile(File(root, "$key.json")); val stream = atomic.startWrite()
        try { stream.write(value.toString().toByteArray(Charsets.UTF_8)); atomic.finishWrite(stream) }
        catch (error: Exception) { atomic.failWrite(stream); throw error }
    }
    private fun vector(value: JSONObject): FloatArray {
        val array = value.getJSONArray("vector")
        check(array.length() in 1..16384) { "acceptance_cache_vector_invalid" }
        return FloatArray(array.length()) { array.getDouble(it).toFloat() }.also {
            check(it.all { number -> number.isFinite() } && it.any { number -> number != 0f })
        }
    }
    fun provider(actual: EmbeddingProvider): EmbeddingProvider = object : EmbeddingProvider by actual {
        override suspend fun embed(text: String) = embedBatch(listOf(text)).singleOrNull()
        override suspend fun embedQuery(text: String) = embedBatch(listOf(text), EmbeddingPurpose.QUERY).singleOrNull()
        override suspend fun embedBatch(texts: List<String>) = embedBatch(texts, EmbeddingPurpose.DOCUMENT)
        override suspend fun embedBatch(texts: List<String>, purpose: EmbeddingPurpose): List<FloatArray?> = lock.withLock {
            if (!actual.isAvailable()) return@withLock texts.map { null }
            val backend = "${actual.identity}:${actual.modelId}"
            val keys = texts.map { key("embedding", backend, purpose.name, it) }
            val values = keys.map { read(it)?.let(::vector) }.toMutableList<FloatArray?>()
            val missing = values.indices.filter { values[it] == null }
            hits += values.size - missing.size; misses += missing.size
            if (missing.isNotEmpty()) {
                val received = actual.embedBatch(missing.map { texts[it] }, purpose)
                check(received.size == missing.size) { "acceptance_cache_response_incomplete" }
                check(backend == "${actual.identity}:${actual.modelId}") { "acceptance_cache_backend_changed" }
                missing.zip(received).forEach { (index, value) ->
                    if (value != null) {
                        val record = JSONObject().put("vector", JSONArray(value.toList()))
                        values[index] = vector(record)
                        write(keys[index], record)
                    }
                }
            }
            values
        }
    }
    fun model(actual: ILanguageModel): ILanguageModel = object : ILanguageModel by actual {
        override suspend fun boundedCompletion(userMessage: String, systemPrompt: String): String? = lock.withLock {
            check(actual.enabled && actual.keyConfigured) { "acceptance_cache_model_disabled" }
            val backend = actual.retrievalIdentity
            val requestKey = key("completion", backend, systemPrompt, userMessage)
            read(requestKey)?.let {
                hits++
                val raw = it.getString("raw")
                observe(userMessage, systemPrompt, raw, true)
                return@withLock raw
            }
            misses++
            actual.boundedCompletion(userMessage, systemPrompt)?.also { raw ->
                check(backend == actual.retrievalIdentity) { "acceptance_cache_backend_changed" }
                check(raw.length <= 4096) { "acceptance_cache_response_too_large" }
                write(requestKey, JSONObject().put("raw", raw))
                observe(userMessage, systemPrompt, raw, false)
            }
        }
    }
}
