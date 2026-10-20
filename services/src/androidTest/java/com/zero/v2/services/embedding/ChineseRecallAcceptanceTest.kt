package com.zero.v2.services.embedding

import android.content.ContextWrapper
import android.os.Debug
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import com.zero.v2.services.retrieval.CurrentRecallPolicy
import com.zero.v2.services.retrieval.PagedSemanticSearch
import com.zero.v2.services.retrieval.RetrievalKit
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Test

/** Independent UID, synthetic data, production native embedding and generic ranking functions. */
class ChineseRecallAcceptanceTest {
    @Test fun frozenChineseCorpus(): Unit = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        check(args.getString("allowNativeRecall") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(context.filesDir, "p5-native")
        val file = File(root, "fixture.json")
        val hash = MessageDigest.getInstance("SHA-256").digest(file.readBytes())
            .joinToString("") { "%02x".format(it) }
        check(hash == args.getString("fixtureSha256"))
        val fixture = JSONObject(file.readText())
        val queries = fixture.getJSONArray("queries")
        val documents = fixture.getJSONArray("documents")
        check(queries.length() > 0 && documents.length() > 5)
        val texts = (0 until documents.length()).associate { i ->
            val doc = documents.getJSONObject(i)
            doc.getString("id") to doc.getString("text")
        }
        check(texts.size == documents.length())
        val provider = LocalBgeProvider(object : ContextWrapper(context) {
            override fun getFilesDir(): File = root
        })
        val identity = provider.identity
        val reference = JSONObject(File(root, "bge-vectors.json").readText())
        val calls = JSONArray()
        val rows = JSONArray()
        val report = JSONObject().put("fixtureSha256", hash).put("identity", identity)
            .put("calls", calls).put("rows", rows).put("cloudCalls", 0)
            .put("personalDataUsed", false).put("inferenceLocation", "phone CPU")
        val output = File(context.filesDir, "chinese-recall-report.json")
        fun save() = output.writeText(report.toString(2))
        fun record(key: String, vector: FloatArray?, elapsed: Long) {
            val expected = reference.getJSONArray(key)
            val error = vector?.takeIf { it.size == expected.length() }?.indices
                ?.maxOf { kotlin.math.abs(vector[it].toDouble() - expected.getDouble(it)) }
            calls.put(JSONObject().put("key", key).put("ms", elapsed).put("success", vector != null)
                .put("maxDesktopError", error ?: JSONObject.NULL)
                .put("parityPassed", error != null && error < 0.0001))
            save()
        }
        try {
            val vectors = mutableMapOf<String, FloatArray?>()
            for ((id, text) in texts) {
                val start = SystemClock.elapsedRealtime()
                val vector = provider.embed(text)
                vectors[id] = vector
                record("document:$id", vector, SystemClock.elapsedRealtime() - start)
            }
            val ids = texts.keys.sorted()
            repeat(3) { iteration ->
                for (index in 0 until queries.length()) {
                    val query = queries.getJSONObject(index)
                    val qid = query.getString("id")
                    val text = query.getString("text")
                    val gold = strings(query.getJSONArray("gold"))
                    val forbidden = strings(query.getJSONArray("forbidden"))
                    check((gold + forbidden).all { it in texts })
                    val start = SystemClock.elapsedRealtime()
                    val vector = provider.embedQuery(text)
                    val embeddingMs = SystemClock.elapsedRealtime() - start
                    val raw = if (vector == null) emptyList() else PagedSemanticSearch.search(
                        ids.size, { after, size -> ids.filter { it > after }.take(size) }, { it },
                        { id -> vectors[id]?.let { RetrievalKit.cosineSimilarity(vector, it) } },
                    )
                    val current = raw.filter { CurrentRecallPolicy.eligible(text, texts.getValue(it)) }
                    val terms = RetrievalKit.queryBigrams(text)
                    val lexical = current.map { id -> id to terms.count { it in texts.getValue(id) } }
                        .filter { it.second > 0 }.sortedWith(compareByDescending<Pair<String, Int>> { it.second }
                            .thenBy { it.first }).map { it.first }
                    val fused = RetrievalKit.rrfFuse(current, lexical, limit = 5, keyOf = { it }).map { it.first }
                    val elapsed = SystemClock.elapsedRealtime() - start
                    val pss = Debug.MemoryInfo().also(Debug::getMemoryInfo).totalPss
                    for ((strategy, ranking) in listOf("cosine" to raw, "current-cosine" to current,
                        "current-RRF60" to fused)) {
                        val found = ranking.take(5)
                        rows.put(JSONObject().put("id", qid).put("iteration", iteration)
                            .put("group", query.getString("group")).put("strategy", strategy)
                            .put("top5", JSONArray(found)).put("answerable", gold.isNotEmpty())
                            .put("recall", if (gold.isEmpty()) JSONObject.NULL else
                                gold.count { it in found }.toDouble() / gold.size)
                            .put("forbiddenHits", forbidden.count { it in found })
                            .put("embeddingMs", embeddingMs).put("allStrategiesMs", elapsed).put("pssKiB", pss))
                    }
                    record("query:$qid", vector, embeddingMs)
                }
            }
            val allRows = (0 until rows.length()).map { rows.getJSONObject(it) }
            val summaries = JSONObject()
            var anyPassed = false
            for (strategy in listOf("cosine", "current-cosine", "current-RRF60")) {
                val selected = allRows.filter { it.getString("strategy") == strategy && it.getBoolean("answerable") }
                val groups = selected.groupBy { it.getString("group") }
                    .mapValues { (_, values) -> values.map { it.getDouble("recall") }.average() }
                val rejectedHits = selected.sumOf { it.getInt("forbiddenHits") }
                val passed = groups.size == 5 && groups.values.all { it >= 0.9 } && rejectedHits == 0
                anyPassed = anyPassed || passed
                summaries.put(strategy, JSONObject().put("groups", JSONObject(groups))
                    .put("forbiddenHitsAcrossRepeats", rejectedHits).put("retrievalGatePassed", passed))
            }
            val durations = allRows.filter { it.getString("strategy") == "cosine" }
                .map { it.getLong("allStrategiesMs") }.sorted()
            val p95 = durations[kotlin.math.ceil(durations.size * 0.95).toInt() - 1]
            val healthy = (0 until calls.length()).all {
                calls.getJSONObject(it).getBoolean("success") && calls.getJSONObject(it).getBoolean("parityPassed")
            } && provider.identity == identity
            report.put("summaries", summaries).put("p95AllStrategiesMs", p95).put("healthy", healthy)
                .put("fullEvidenceGatePassed", false).put("noSupport", "diagnostic only; no abstention classifier")
            save()
            assertTrue("All calls including no-support probes must succeed and match desktop", healthy)
            assertTrue("Phone latency budget", p95 <= fixture.getLong("queryP95BudgetMs"))
            assertTrue("See retained group failures; no relaxed gate", anyPassed)
        } finally { save() }
    }

    private fun strings(array: JSONArray): List<String> = (0 until array.length()).map { array.getString(it) }
}
