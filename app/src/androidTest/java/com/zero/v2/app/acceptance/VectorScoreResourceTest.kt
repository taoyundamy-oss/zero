package com.zero.v2.app.acceptance

import android.os.Debug
import androidx.test.platform.app.InstrumentationRegistry
import com.zero.v2.services.retrieval.EncodedCosineScorer
import com.zero.v2.services.retrieval.RetrievalKit
import java.io.File
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VectorScoreResourceTest {
    @Test fun encodedScorePreservesValuesWithoutPerRowVectorAllocation() {
        val query = FloatArray(1024) { (it % 11 - 5).toFloat() }
        val source = FloatArray(1024) { (it % 7 - 3).toFloat() }
        val blob = RetrievalKit.encodeEmbedding(source)
        val expected = RetrievalKit.cosineSimilarity(query, source)
        val scorer = EncodedCosineScorer(query)
        fun measure(score: () -> Float): JSONObject {
            repeat(100) { assertEquals(expected, score(), 0f) }
            val before = Debug.getRuntimeStat("art.gc.bytes-allocated").toLong()
            val started = System.nanoTime()
            repeat(10000) { assertEquals(expected, score(), 0f) }
            return JSONObject().put("allocatedBytes", Debug.getRuntimeStat("art.gc.bytes-allocated").toLong() - before)
                .put("elapsedMs", (System.nanoTime() - started) / 1_000_000.0)
        }
        val old = measure { RetrievalKit.cosineSimilarity(query, RetrievalKit.decodeEmbedding(blob)) }
        val current = measure { scorer.score(blob)!! }
        File(InstrumentationRegistry.getInstrumentation().targetContext.filesDir, "acceptance-a4-vector-score.json")
            .writeText(JSONObject().put("reference", old).put("production", current).put("rows", 10000)
                .put("dimensions", 1024).put("scope", "isolated_score_not_sqlite_or_full_retrieval_latency").toString(2))
        assertTrue("encoded scorer must reduce per-row allocation", current.getLong("allocatedBytes") < old.getLong("allocatedBytes"))
    }
}
