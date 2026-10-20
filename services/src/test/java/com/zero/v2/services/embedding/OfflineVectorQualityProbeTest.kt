package com.zero.v2.services.embedding

import com.zero.v2.core.data.entity.VectorVersionEntity
import java.io.File
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Explicit offline acceptance probe; consumes measured synthetic vectors, never personal data. */
class OfflineVectorQualityProbeTest {
    @Test fun measuredVectorsRunThroughProductionGate(): Unit = runBlocking {
        val folder = System.getenv("ZERO_VECTOR_PROBE_DIR")
        assumeTrue("Run explicitly with verified offline vector fixture", folder != null)
        val root = File(requireNotNull(folder))
        val model = System.getenv("ZERO_VECTOR_PROBE_MODEL")
        require(model == null || model in setOf("bge", "qwen", "e5"))
        val vectors = JSONObject(File(root, if (model == null) "synthetic-bge-vectors.json"
            else "$model-synthetic-vectors.json").readText())
        fun vector(text: String, query: Boolean): FloatArray {
            val key = if (model == null) text else "${if (query) "query" else "document"}:$text"
            val values = vectors.getJSONArray(key)
            return FloatArray(values.length()) { values.getDouble(it).toFloat() }
        }
        val provider = object : EmbeddingProvider {
            override val modelId = "offline-${model ?: "bge"}-measured-vectors"
            override suspend fun isAvailable() = true
            override suspend fun embed(text: String): FloatArray = vector(text, false)
            override suspend fun embedQuery(text: String): FloatArray = vector(text, true)
            override suspend fun embedBatch(texts: List<String>): List<FloatArray?> = texts.map { embed(it) }
            override suspend fun embedBatch(texts: List<String>, purpose: EmbeddingPurpose): List<FloatArray?> =
                texts.map { vector(it, purpose == EmbeddingPurpose.QUERY) }
        }
        val reports = JSONArray()
        for (domain in VectorDomain.entries) {
            val candidate = VectorVersionEntity("offline-probe", domain.key, provider.spaceId(domain.contract),
                vector(VectorRecallBenchmark.sources.first().text, false).size,
                12, null, "VALIDATING", "synthetic-only", 0)
            val result = VectorBenchmarkGate(provider).evaluate(candidate, null, domain)
            assertEquals(12, result.evidence.evaluatedSamples)
            reports.put(JSONObject(result.evidence.report).put("domain", domain.key))
        }
        File(root, "${model ?: "bge"}-production-gate.json").writeText(JSONObject()
            .put("scope", "desktop-measured-vectors-through-production-evaluator")
            .put("androidInference", false).put("cloudCalls", 0).put("reports", reports).toString(2))
        // Model acceptance is recorded separately; running the probe is not a model pass.
    }
}
