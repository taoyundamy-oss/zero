package com.zero.v2.app.acceptance

import android.os.Debug
import androidx.test.platform.app.InstrumentationRegistry
import com.zero.v2.services.llm.ModelSelector
import com.zero.v2.services.retrieval.BoundedSemanticReranker
import com.zero.v2.services.retrieval.EvidencePolicy
import java.io.File
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PolicyDigestResourceTest {
    @Test fun policyDigestMustKeepItsKeyWithoutFormatterAllocation() {
        val reranker = BoundedSemanticReranker(ModelSelector())
        val input = "${BoundedSemanticReranker.VERSION}:null"
        val expected = EvidencePolicy.bodyDigest(input)
        repeat(100) { assertEquals(expected, reranker.policyKey) }
        fun measure(block: () -> String): JSONObject {
            val before = Debug.getRuntimeStat("art.gc.bytes-allocated").toLong()
            val started = System.nanoTime()
            repeat(1000) { assertEquals(expected, block()) }
            val elapsed = (System.nanoTime() - started) / 1_000_000.0
            val allocated = Debug.getRuntimeStat("art.gc.bytes-allocated").toLong() - before
            return JSONObject().put("allocatedBytes", allocated).put("elapsedMs", elapsed)
        }
        val reference = measure { EvidencePolicy.bodyDigest(input) }
        val production = measure { reranker.policyKey }
        val report = JSONObject().put("reference", reference).put("production", production)
            .put("policyKey", expected).put("providerCalls", 0).put("iterations", 1000)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        File(context.filesDir, "acceptance-a4-policy-digest.json").writeText(report.toString(2))
        streamAcceptanceReport(report)
        assertTrue("policy digest allocation exceeds equivalent encoding plus selector overhead",
            production.getLong("allocatedBytes") <= reference.getLong("allocatedBytes") * 4 + 65536)
    }
}
