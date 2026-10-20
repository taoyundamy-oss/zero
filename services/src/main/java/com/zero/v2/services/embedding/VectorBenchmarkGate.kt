package com.zero.v2.services.embedding

import com.zero.v2.core.data.entity.VectorValidationEntity
import com.zero.v2.core.data.entity.VectorVersionEntity
import com.zero.v2.services.retrieval.RetrievalKit
import com.zero.v2.services.retrieval.BoundedSemanticReranker
import com.zero.v2.services.retrieval.CurrentRecallPolicy
import javax.inject.Inject
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject

/** Actual provider scoring over independent text, plus a receipt bound to the personal candidate. */
class VectorBenchmarkGate @Inject constructor(
    private val provider: EmbeddingProvider,
    private val reranker: BoundedSemanticReranker? = null,
) {
    val rankingPolicy: String get() = "${reranker?.policyKey ?: "vector-only-v1"}:${CurrentRecallPolicy.VERSION}"

    fun checkRankingPolicy(evidence: VectorValidationEntity) {
        val report = JSONObject(evidence.report)
        if (report.optString("scope") == "independent_benchmark") {
            check(report.optString("rankingPolicy", "vector-only-v1") == rankingPolicy) {
                "vector_ranking_policy_changed"
            }
            check(VectorDomain.entries.any { VectorRecallBenchmark.dataset(it).digest() == evidence.datasetDigest }) {
                "vector_benchmark_dataset_changed"
            }
        }
    }
    internal suspend fun evaluate(
        candidate: VectorVersionEntity, baseline: VectorValidationEntity?, domain: VectorDomain,
    ): VectorRecallEvaluator.Result {
        val data = VectorRecallBenchmark.dataset(domain)
        val policy = rankingPolicy
        check(baseline == null || baseline.datasetDigest in
            setOf(data.digest(), VectorRecallBenchmark.legacyDataset(domain).digest())) {
            "vector_baseline_dataset_changed"
        }
        val documents = vectors(candidate, domain, VectorRecallBenchmark.sources.map(domain::input))
        val queries = vectors(candidate, domain, data.samples.map { it.query }, EmbeddingPurpose.QUERY)
        val scores = data.samples.zip(queries).map { (sample, query) ->
            val ordered = documents.indices.sortedByDescending { RetrievalKit.cosineSimilarity(query, documents[it]) }
                .map { VectorRecallBenchmark.sources[it] }
            val eligible = ordered.filter { CurrentRecallPolicy.eligible(sample.query, it.text) }
            val (ranked, status) = reranker?.rank(sample.query, eligible, { it.id }, { domain.input(it) })
                ?: (eligible to "vector_only")
            val source = ranked.firstOrNull()?.id
            val raw = ordered.first().id
            JSONObject().put("id", sample.id).put("kind", sample.kind.name)
                .put("stratum", VectorRecallBenchmark.stratum(sample))
                .put("recall", if (source in sample.relevant) 1.0 else 0.0)
                .put("forbiddenHits", if (source in sample.forbidden) 1 else 0)
                .put("vectorRecall", if (raw in sample.relevant) 1.0 else 0.0)
                .put("vectorForbiddenHits", if (raw in sample.forbidden) 1 else 0).put("rerankStatus", status)
        }
        check(policy == rankingPolicy) { "vector_ranking_policy_changed" }
        return report(candidate, baseline, data, scores, policy)
    }

    private fun report(
        candidate: VectorVersionEntity, baseline: VectorValidationEntity?, data: VectorRecallDataset,
        scores: List<JSONObject>, policy: String,
    ): VectorRecallEvaluator.Result {
        val groups = data.samples.indices.groupBy { data.samples[it].kind.name }.mapValues { (_, indices) ->
            indices.map { scores[it].getDouble("recall") }.average()
        }
        val recall = scores.map { it.getDouble("recall") }.average()
        val strata = scores.groupBy { it.getString("stratum") }.mapValues { (_, rows) ->
            rows.map { it.getDouble("recall") }.average()
        }
        val completed = scores.all { it.getString("rerankStatus") in setOf("applied", "cache_hit", "vector_only") }
        val accepted = completed && meetsFloor(groups, null, data.minimumRecall) &&
            meetsFloor(strata, null, data.minimumRecall) && preservesBaseline(baseline, data, scores, groups, strata) &&
            scores.all { it.getInt("forbiddenHits") == 0 }
        val report = JSONObject().put("schema", 1).put("scope", "independent_benchmark")
            .put("space", candidate.space).put("baseVersion", candidate.baseVersion ?: JSONObject.NULL)
            .put("limit", data.limit).put("accepted", accepted).put("groups", JSONObject(groups))
            .put("languageKindGroups", JSONObject(strata))
            .put("baselineComparisonScope", when (baseline?.datasetDigest) {
                null -> "none"
                VectorRecallBenchmark.legacyDataset(data.domain).digest() -> "zh-only-legacy"
                else -> "zh-en"
            })
            .put("rankingPolicy", policy).put("retryable", !completed)
            .put("pipeline", if (reranker == null) "vector_current_filter"
                else "vector_current_filter_then_bounded_semantic")
            .put("currentSourcePolicy", CurrentRecallPolicy.VERSION)
            .put("vectorRecall", scores.map { it.getDouble("vectorRecall") }.average())
            .put("vectorGroups", JSONObject(scores.groupBy { it.getString("kind") }.mapValues { (_, rows) ->
                rows.map { it.getDouble("vectorRecall") }.average()
            }))
            .put("samples", JSONArray(scores)).toString()
        return VectorRecallEvaluator.Result(accepted, VectorValidationEntity(
            candidate.id, candidate.digest, data.version, data.digest(), data.samples.size, scores.size,
            baseline?.candidateRecall ?: 0.0, recall, data.minimumRecall, report,
        ))
    }

    private fun meetsFloor(groups: Map<String, Double>, previous: JSONObject?, minimum: Double): Boolean =
        groups.all { (name, value) -> value >= minimum && value >= (previous?.getDouble(name) ?: 0.0) }

    private fun preservesBaseline(
        baseline: VectorValidationEntity?, data: VectorRecallDataset, scores: List<JSONObject>,
        groups: Map<String, Double>, strata: Map<String, Double>,
    ): Boolean {
        if (baseline == null) return true
        val old = JSONObject(baseline.report)
        return if (baseline.datasetDigest == VectorRecallBenchmark.legacyDataset(data.domain).digest()) {
            val chinese = scores.filter { it.getString("stratum").startsWith("zh:") }
            chinese.map { it.getDouble("recall") }.average() >= baseline.candidateRecall &&
                VectorRecallDataset.Kind.entries.all { kind ->
                    strata.getValue("zh:${kind.name}") >= old.getJSONObject("groups").getDouble(kind.name)
                }
        } else {
            scores.map { it.getDouble("recall") }.average() >= baseline.candidateRecall &&
                meetsFloor(groups, old.getJSONObject("groups"), 0.0) &&
                meetsFloor(strata, old.getJSONObject("languageKindGroups"), 0.0)
        }
    }

    private suspend fun vectors(
        candidate: VectorVersionEntity, domain: VectorDomain, texts: List<String>,
        purpose: EmbeddingPurpose = EmbeddingPurpose.DOCUMENT,
    ): List<FloatArray> {
        check(candidate.space == provider.spaceId(domain.contract)) { "vector_provider_changed" }
        val values = checkNotNull(withTimeoutOrNull(REQUEST_TIMEOUT_MS) { provider.embedBatch(texts, purpose) }) {
            "vector_evaluation_timeout"
        }
        if (!provider.isAvailable()) throw EmbeddingAuthorizationException()
        check(candidate.space == provider.spaceId(domain.contract)) { "vector_provider_changed" }
        check(values.size == texts.size) { "vector_evaluation_incomplete" }
        return values.map { value ->
            checkNotNull(value) { "vector_evaluation_incomplete" }
            check(value.size == candidate.dimensions && value.all { it.isFinite() } && value.any { it != 0f }) {
                "vector_evaluation_query_invalid"
            }
            value
        }
    }
}
