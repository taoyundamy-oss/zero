package com.zero.v2.app.acceptance

import android.util.AtomicFile
import com.zero.v2.services.embedding.EmbeddingProvider
import com.zero.v2.services.embedding.EmbeddingPurpose
import com.zero.v2.services.llm.ILanguageModel
import java.io.File
import org.json.JSONObject

/** User-authorized session ledger. Reserve before sending; interrupted/unknown usage stays charged. */
internal class RecallRequestBudget(file: File, private val inputUsage: () -> Long = { 0L }) {
    private val atomic = AtomicFile(file)
    private val state = if (file.exists()) JSONObject(atomic.readFully().toString(Charsets.UTF_8))
        else JSONObject().put("embeddingCharged", 0L).put("rankRequests", 0)
    // User approval on 2026-10-05 raises cumulative caps; the existing charged totals are retained.
    init {
        check(state.getLong("embeddingCharged") in 0..1_500_000 && state.getInt("rankRequests") in 0..384)
        state.put("embeddingLimit", 1_500_000).put("rankLimit", 384)
    }
    @Synchronized fun snapshot(): JSONObject = JSONObject(state.toString())
    @Synchronized private fun reserve(tokens: Long = 0, rank: Boolean = false) {
        val next = state.getLong("embeddingCharged") + tokens
        val calls = state.getInt("rankRequests") + if (rank) 1 else 0
        check(next <= 1_500_000 && calls <= 384) { "a4_authorized_budget_exhausted" }
        state.put("embeddingCharged", next).put("rankRequests", calls)
        save()
    }
    @Synchronized private fun settle(reserved: Long, reported: Long) {
        if (reported in 1..reserved) {
            state.put("embeddingCharged", state.getLong("embeddingCharged") - reserved + reported)
            save()
        }
    }
    private fun save() {
        val stream = atomic.startWrite()
        try { stream.write(state.toString().toByteArray()); atomic.finishWrite(stream) }
        catch (error: Exception) { atomic.failWrite(stream); throw error }
    }
    fun provider(actual: EmbeddingProvider): EmbeddingProvider = object : EmbeddingProvider by actual {
        override suspend fun embed(text: String) = embedBatch(listOf(text)).firstOrNull()
        override suspend fun embedQuery(text: String) = embedBatch(listOf(text), EmbeddingPurpose.QUERY).firstOrNull()
        override suspend fun embedBatch(texts: List<String>) = embedBatch(texts, EmbeddingPurpose.DOCUMENT)
        override suspend fun embedBatch(texts: List<String>, purpose: EmbeddingPurpose): List<FloatArray?> {
            val result = mutableListOf<FloatArray?>()
            for (chunk in texts.chunked(10)) {
                val reserved = chunk.sumOf { it.toByteArray().size.toLong() + 64 }
                reserve(reserved)
                val before = inputUsage()
                val values = actual.embedBatch(chunk, purpose)
                settle(reserved, inputUsage() - before)
                result.addAll(values)
            }
            return result
        }
    }
    fun model(actual: ILanguageModel): ILanguageModel = object : ILanguageModel by actual {
        override suspend fun boundedCompletion(userMessage: String, systemPrompt: String): String? {
            reserve(rank = true)
            return actual.boundedCompletion(userMessage, systemPrompt)
        }
    }
}

