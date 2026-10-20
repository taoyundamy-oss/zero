package com.zero.v2.services.embedding

import com.zero.v2.core.data.entity.VectorVersionEntity
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test

class VectorBenchmarkPolicyTest {
    @Test fun legacySubsetMatchesPreviouslyPublishedDeviceReceipts() {
        assertEquals("b9168b043329939d4f404a79d5479630243461a569b63cf3566c8b28b7815354",
            VectorRecallBenchmark.legacyDataset(VectorDomain.MEMORY).digest())
        assertEquals("d7eeb67da57915ddf4b67adf5311e1e4c5fe7e26e30d465fca4b55ca7128d805",
            VectorRecallBenchmark.legacyDataset(VectorDomain.KNOWLEDGE).digest())
    }

    @Test fun chineseSuccessCannotApproveFailedEnglishRetrieval(): Unit = runBlocking {
        val data = VectorRecallBenchmark.dataset(VectorDomain.MEMORY)
        val sources = VectorRecallBenchmark.sources
        val provider = object : EmbeddingProvider {
            override val modelId = "language-gap-fixture"
            override suspend fun isAvailable() = true
            override suspend fun embed(text: String) = embedBatch(listOf(text)).single()
            override suspend fun embedBatch(texts: List<String>): List<FloatArray?> = texts.map { text ->
                val sample = data.samples.firstOrNull { it.query == text }
                val target = sample?.let {
                    if (it.query.any { c -> c in 'a'..'z' }) "support-wrong" else it.relevant.first()
                }
                val index = sources.indexOfFirst { if (sample == null) text.contains(it.text) else it.id == target }
                FloatArray(sources.size) { if (it == index) 1f else 0f }
            }
        }
        val candidate = VectorVersionEntity("fixture", "memory", provider.spaceId(VectorDomain.MEMORY.contract),
            sources.size, sources.size, null, "VALIDATING", "fixture-digest", 0)
        val result = VectorBenchmarkGate(provider).evaluate(candidate, null, VectorDomain.MEMORY)
        assertFalse("Chinese-only success must not approve a multilingual candidate", result.accepted)
        assertEquals(12, result.evidence.evaluatedSamples)
        val legacyData = VectorRecallBenchmark.legacyDataset(VectorDomain.MEMORY)
        val legacy = result.evidence.copy(datasetDigest = legacyData.digest(), datasetVersion = legacyData.version,
            candidateRecall = 1.0, expectedSamples = 6, evaluatedSamples = 6,
            report = JSONObject().put("groups", JSONObject(VectorRecallDataset.Kind.entries.associate {
                it.name to 1.0
            })).toString())
        assertFalse(VectorBenchmarkGate(provider).evaluate(candidate, legacy, VectorDomain.MEMORY).accepted)
    }

    @Test fun evaluationUsesCurrentSourcePolicyAndStillReportsUnfilteredVectorScore(): Unit = runBlocking {
        val data = VectorRecallBenchmark.dataset(VectorDomain.MEMORY)
        val sources = VectorRecallBenchmark.sources
        val provider = object : EmbeddingProvider {
            override val modelId = "deterministic-policy-fixture"
            override suspend fun isAvailable() = true
            override suspend fun embed(text: String) = embedBatch(listOf(text)).single()
            override suspend fun embedBatch(texts: List<String>): List<FloatArray?> = texts.map { text ->
                val source = sources.indexOfFirst { text.contains(it.text) }
                if (source >= 0) FloatArray(sources.size) { if (it == source) 1f else 0f }
                else {
                    val sample = data.samples.single { it.query == text }
                    val positive = sources.indexOfFirst { it.id in sample.relevant }
                    val negative = sources.indexOfFirst { it.id in sample.forbidden }
                    FloatArray(sources.size) {
                        when { "room" in sample.relevant && it == negative -> 1f; it == positive -> .8f; else -> 0f }
                    }
                }
            }
        }
        val gate = VectorBenchmarkGate(provider)
        val candidate = VectorVersionEntity("fixture", "memory", provider.spaceId(VectorDomain.MEMORY.contract),
            sources.size, sources.size, null, "VALIDATING", "fixture-digest", 0)
        val result = gate.evaluate(candidate, null, VectorDomain.MEMORY)
        assertTrue("gate must evaluate the same current-source exclusion as retrieval", result.accepted)
        val report = JSONObject(result.evidence.report)
        assertEquals(5.0 / 6.0, report.getDouble("vectorRecall"), 0.0001)
        assertEquals(1.0, result.evidence.candidateRecall, 0.0001)
        gate.checkRankingPolicy(result.evidence)
        assertEquals(6, report.getJSONObject("languageKindGroups").length())
        val legacyData = data.copy(version = "zero-independent-recall-v1",
            samples = data.samples.filterNot { it.id.endsWith(":en") })
        val legacy = result.evidence.copy(datasetVersion = legacyData.version, datasetDigest = legacyData.digest(),
            expectedSamples = 6, evaluatedSamples = 6)
        val upgraded = gate.evaluate(candidate, legacy, VectorDomain.MEMORY)
        assertTrue("Known old Chinese baseline must not deadlock an otherwise valid rebuild", upgraded.accepted)
        assertEquals("zh-only-legacy", JSONObject(upgraded.evidence.report).getString("baselineComparisonScope"))
        assertThrows(IllegalStateException::class.java) {
            gate.checkRankingPolicy(result.evidence.copy(datasetDigest = "previous-chinese-only-digest"))
        }
        val stale = JSONObject(result.evidence.report).put("rankingPolicy", "vector-only-v1").toString()
        assertThrows(IllegalStateException::class.java) {
            gate.checkRankingPolicy(result.evidence.copy(report = stale))
        }
        Unit
    }
}
