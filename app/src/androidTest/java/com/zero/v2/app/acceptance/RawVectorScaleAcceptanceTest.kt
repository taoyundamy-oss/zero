package com.zero.v2.app.acceptance

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.zero.v2.core.data.db.ZeroDatabase
import com.zero.v2.core.data.entity.*
import com.zero.v2.services.embedding.*
import com.zero.v2.services.retrieval.CurrentRecallPolicy
import com.zero.v2.services.retrieval.RetrievalKit
import dagger.hilt.android.EntryPointAccessors
import java.io.File
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test

/** Real embeddings and actual storage gate; candidate-only metrics never grant publication. */
class RawVectorScaleAcceptanceTest {
    @Test fun rawCandidatesAndIndependentGate() = runBlocking {
        check(InstrumentationRegistry.getArguments().getString("allowRealProvider") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val size = InstrumentationRegistry.getArguments().getString("corpusSize")!!.toInt()
        val corpus = ScaleRecallFixture.corpus(size)
        val entry = EntryPointAccessors.fromApplication(context, ProviderAcceptanceEntryPoint::class.java)
        val budget = RecallRequestBudget(File(context.filesDir, "acceptance-a4-authorized-budget.json")) {
            entry.llmClient().usageTotal.value.inputTokens
        }
        val provider = budget.provider(entry.embeddingProvider())
        val db = Room.inMemoryDatabaseBuilder(context, ZeroDatabase::class.java).build()
        val root = File(context.cacheDir, "a4-raw-${java.util.UUID.randomUUID()}")
        val journal = VectorEvaluationJournal(object : android.content.ContextWrapper(context) {
            override fun getNoBackupFilesDir(): File = root
        })
        val worker = VectorIndexWorker(db.vectorIndexDao(), provider, journal, VectorBenchmarkGate(provider))
        val rows = JSONArray()
        val report = JSONObject().put("scope", "unpublished_candidate_cosine_with_current_filter_not_tool_pipeline")
            .put("size", size).put("digest", ScaleRecallFixture.digest(size)).put("samples", rows).put("completed", false)
        val builds = JSONObject(); report.put("builds", builds)
        val output = File(context.filesDir, "acceptance-a4-raw-$size.json")
        fun save() { report.put("budget", budget.snapshot()); output.writeText(report.toString(2)) }
        try {
            save()
            corpus.chunked(100).forEachIndexed { batch, items ->
                val start = batch * 100
                db.memoryDao().insertAll(items.mapIndexed { i, row -> MemoryEntity("row-${start + i}", row.text) })
                items.forEachIndexed { i, row ->
                    val id = "row-${start + i}"
                    db.kbDao().insertDoc(KbDocEntity(id, "$id.md", row.title, 0, 0, 1, 0))
                    db.kbDao().insertChunks(listOf(KbChunkEntity(id, id, 0, headingPath = "", text = row.text, charLen = row.text.length, updatedAt = 0)))
                }
            }
            for (domain in VectorDomain.entries) {
                builds.put(domain.key, worker.build(domain).name)
                report.put("gate-${domain.key}", worker.evaluationReport(domain)?.let { JSONObject(it) } ?: JSONObject.NULL)
                save()
                val version = db.vectorIndexDao().versions(domain.key).first()
                check(db.vectorIndexDao().countEntries(version.id) == size) { "raw_candidate_incomplete" }
                ScaleRecallFixture.samples.forEachIndexed { index, sample ->
                    val query = checkNotNull(provider.embedQuery(sample.query))
                    val started = System.nanoTime()
                    val top = mutableListOf<Pair<Int, Float>>()
                    var after = ""
                    while (true) {
                        val page = db.vectorIndexDao().candidatePage(version.id, after, 128)
                        if (page.isEmpty()) break
                        page.filter { CurrentRecallPolicy.eligible(sample.query, it.sourceText) }.forEach {
                            val score = RetrievalKit.cosineSimilarity(query, RetrievalKit.decodeEmbedding(it.vector))
                            check(score.isFinite())
                            top.add(it.sourceId.removePrefix("row-").toInt() to score)
                        }
                        top.sortByDescending { it.second }
                        if (top.size > 3) top.subList(3, top.size).clear()
                        after = page.last().sourceId
                    }
                    val elapsed = (System.nanoTime() - started) / 1_000_000.0
                    for (k in listOf(1, 3)) {
                        val returned = top.take(k).map { it.first }.toSet()
                        rows.put(JSONObject().put("domain", domain.key).put("sample", index).put("kind", sample.kind).put("k", k)
                            .put("recall", returned.intersect(sample.relevant).size.toDouble() / sample.relevant.size)
                            .put("forbiddenHits", returned.intersect(sample.forbidden).size).put("scanMs", elapsed))
                    }
                    save()
                }
            }
            report.put("completed", true); save()
        } finally { db.close(); root.deleteRecursively() }
    }
}
