package com.zero.v2.services.embedding

import com.zero.v2.core.data.dao.VectorIndexDao
import com.zero.v2.core.data.entity.VectorValidationEntity
import com.zero.v2.core.data.entity.VectorVersionEntity
import com.zero.v2.services.retrieval.PagedSemanticSearch
import com.zero.v2.services.retrieval.RetrievalKit
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject

/** Evaluates every labelled query; exceptions/cancellation produce no approval receipt. */
internal class VectorRecallEvaluator(
    private val dao: VectorIndexDao,
    private val provider: EmbeddingProvider,
) {
    data class Result(val accepted: Boolean, val evidence: VectorValidationEntity)
    private data class Score(val id: String, val kind: String, val recall: Double, val forbiddenHits: Int)

    suspend fun evaluate(id: String, data: VectorRecallDataset): Result {
        val candidate = checkNotNull(dao.version(id))
        check(candidate.state == "VALIDATING" && candidate.domain == data.domain.key)
        checkSpace(candidate, data)
        val baseline = candidate.baseVersion?.let { checkNotNull(dao.validation(it)) }
        check(baseline == null ||
            (baseline.datasetDigest == data.digest() && baseline.datasetVersion == data.version)) {
            "vector_baseline_dataset_changed"
        }
        val sources = VectorRecallSources.resolve(dao, id, data)
        val scores = data.samples.map { sample ->
            score(candidate, data, sample.copy(
                relevant = sample.relevant.map { sources.getValue(it) }.toSet(),
                forbidden = sample.forbidden.map { sources.getValue(it) }.toSet(),
            ))
        }
        checkSpace(candidate, data)
        val groups = scores.groupBy { it.kind }.mapValues { (_, rows) -> rows.map { it.recall }.average() }
        val recall = scores.map { it.recall }.average()
        val previousGroups = baseline?.let { JSONObject(it.report).getJSONObject("groups") }
        val accepted = recall >= (baseline?.candidateRecall ?: 0.0) && groups.all { (kind, value) ->
            value >= data.minimumRecall && value >= (previousGroups?.getDouble(kind) ?: 0.0)
        } && scores.all { it.forbiddenHits == 0 }
        val report = JSONObject().put("schema", 1).put("space", candidate.space)
            .put("scope", "personal_labelled_vector").put("pipeline", "vector").put("rankingPolicy", "vector-only-v1")
            .put("baseVersion", candidate.baseVersion ?: JSONObject.NULL).put("limit", data.limit)
            .put("accepted", accepted).put("groups", JSONObject(groups))
            .put("samples", JSONArray(scores.map { row ->
                JSONObject().put("id", row.id).put("kind", row.kind)
                    .put("recall", row.recall).put("forbiddenHits", row.forbiddenHits)
            })).toString()
        return Result(accepted, VectorValidationEntity(
            candidate.id, candidate.digest, data.version, data.digest(), data.samples.size, scores.size,
            baseline?.candidateRecall ?: 0.0, recall, data.minimumRecall, report,
        ))
    }

    private suspend fun score(
        candidate: VectorVersionEntity, data: VectorRecallDataset, sample: VectorRecallDataset.Sample,
    ): Score {
        val labels = sample.relevant + sample.forbidden
        val sources = dao.entriesFor(candidate.id, labels.toList())
        check(sources.size == labels.size) { "vector_evaluation_source_missing" }
        require(sources.none { it.sourceText.trim() == sample.query.trim() }) { "vector_evaluation_self_query" }
        checkSpace(candidate, data)
        val query = withTimeout(REQUEST_TIMEOUT_MS) { checkNotNull(provider.embedQuery(sample.query)) }
        checkSpace(candidate, data)
        check(query.size == candidate.dimensions && query.all { it.isFinite() } && query.any { it != 0f }) {
            "vector_evaluation_query_invalid"
        }
        val found = PagedSemanticSearch.search(
            data.limit, { after, size -> dao.candidatePage(candidate.id, after, size) }, { it.sourceId },
            { RetrievalKit.cosineSimilarity(query, RetrievalKit.decodeEmbedding(it.vector)) },
        ).map { it.sourceId }.toSet()
        return Score(sample.id, sample.kind.name,
            found.intersect(sample.relevant).size.toDouble() / sample.relevant.size,
            found.intersect(sample.forbidden).size)
    }

    private fun checkSpace(candidate: VectorVersionEntity, data: VectorRecallDataset) {
        check(candidate.space == provider.spaceId(data.domain.contract)) { "vector_provider_changed" }
    }
}

