package com.zero.v2.services.embedding

import android.content.ContextWrapper
import android.os.Debug
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import com.zero.v2.services.retrieval.PagedSemanticSearch
import com.zero.v2.services.retrieval.RetrievalKit
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Test

/** Explicit offline inference in the independent services test UID. Never opens the daily database. */
class NativeRecallAcceptanceTest {
    @Test fun frozenCorpusRecallAndLatency(): Unit = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        check(args.getString("allowNativeRecall") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(context.filesDir, "p5-native")
        val fixtureFile = File(root, "fixture.json")
        val digest = MessageDigest.getInstance("SHA-256").digest(fixtureFile.readBytes())
            .joinToString("") { "%02x".format(it) }
        check(digest == args.getString("fixtureSha256"))
        val fixture = JSONObject(fixtureFile.readText())
        val provider = LocalBgeProvider(object : ContextWrapper(context) {
            override fun getFilesDir(): File = root
        })
        val identity = provider.identity
        val samples = JSONArray()
        val report = JSONObject().put("fixtureSha256", digest).put("identity", identity)
            .put("cloudRequests", 0).put("personalDatabaseUsed", false)
            .put("inferenceLocation", "phone CPU").put("samples", samples)
        val output = File(context.filesDir, "native-recall-report.json")
        fun save() { output.writeText(report.toString(2)) }
        var accepted = false
        try {
            val docs = fixture.getJSONArray("documents")
            val vectors = mutableMapOf<String, FloatArray?>()
            val indexStart = SystemClock.elapsedRealtime()
            for (i in 0 until docs.length()) {
                val item = docs.getJSONObject(i)
                val start = SystemClock.elapsedRealtime()
                val vector = provider.embed(item.getString("text"))
                vectors[item.getString("id")] = vector
                samples.put(JSONObject().put("phase", "index").put("id", item.getString("id"))
                    .put("cold", i == 0).put("latencyMs", SystemClock.elapsedRealtime() - start)
                    .put("success", vector != null))
                save()
            }
            report.put("indexMs", SystemClock.elapsedRealtime() - indexStart)
            val ids = vectors.keys.sorted()
            val queries = fixture.getJSONArray("queries")
            val hits = mutableMapOf<String, MutableList<Boolean>>()
            repeat(3) { repeat ->
                for (i in 0 until queries.length()) {
                    val query = queries.getJSONObject(i)
                    val start = SystemClock.elapsedRealtime()
                    val vector = provider.embedQuery(query.getString("text"))
                    val embeddingMs = SystemClock.elapsedRealtime() - start
                    val found = if (vector == null) emptyList() else PagedSemanticSearch.search(
                        5, { after, size -> ids.filter { it > after }.take(size) }, { it },
                        { id -> vectors[id]?.let { RetrievalKit.cosineSimilarity(vector, it) } },
                    )
                    val elapsed = SystemClock.elapsedRealtime() - start
                    val gold = query.optString("gold")
                    val hit = gold.isNotEmpty() && gold in found
                    if (gold.isNotEmpty()) hits.getOrPut(query.getString("language")) { mutableListOf() }.add(hit)
                    samples.put(JSONObject().put("phase", "query").put("repeat", repeat)
                        .put("id", query.getString("id")).put("language", query.getString("language"))
                        .put("embeddingMs", embeddingMs).put("latencyMs", elapsed)
                        .put("success", vector != null).put("top5", JSONArray(found))
                        .put("hit", hit).put("answerable", gold.isNotEmpty())
                        .put("pssKb", Debug.MemoryInfo().also(Debug::getMemoryInfo).totalPss))
                    save()
                }
            }
            val quality = hits.all { (_, group) -> group.count { it }.toDouble() / group.size >= 0.9 }
            val timings = (0 until samples.length()).map { samples.getJSONObject(it) }
                .filter { it.getString("phase") == "query" }.map { it.getLong("latencyMs") }.sorted()
            val p95 = timings[(kotlin.math.ceil(timings.size * 0.95).toInt() - 1).coerceAtLeast(0)]
            accepted = quality && p95 <= fixture.getLong("queryP95BudgetMs") &&
                vectors.values.all { it != null } && provider.identity == identity
            report.put("qualityPassed", quality).put("queryP95Ms", p95).put("accepted", accepted)
                .put("scope", "raw vector recall; no evidence classification or abstention guarantee")
            save()
        } finally { save() }
        assertTrue("See native-recall-report.json; rejected samples remain in denominator", accepted)
    }
}
