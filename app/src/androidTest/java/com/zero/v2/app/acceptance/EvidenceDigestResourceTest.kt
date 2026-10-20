package com.zero.v2.app.acceptance

import android.os.Debug
import androidx.test.platform.app.InstrumentationRegistry
import com.zero.v2.services.retrieval.EvidencePolicy
import java.io.File
import java.security.MessageDigest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** ART allocation comparison over the production digest seam; synthetic text only. */
class EvidenceDigestResourceTest {
    @Test fun digestAllocationMustStayNearEquivalentEncoding() {
        val body = "用户更正后培训地点为501室；保留历史来源。".repeat(8)
        fun compact(text: String): String {
            val bytes = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
            val digits = "0123456789abcdef"
            return buildString(bytes.size * 2) {
                for (byte in bytes) {
                    val value = byte.toInt() and 255
                    append(digits[value ushr 4]); append(digits[value and 15])
                }
            }
        }
        val expected = compact(body)
        assertEquals(expected, EvidencePolicy.bodyDigest(body))
        fun measure(encode: (String) -> String): JSONObject {
            repeat(50) { assertEquals(expected, encode(body)) }
            val allocatedBefore = Debug.getRuntimeStat("art.gc.bytes-allocated").toLong()
            val started = System.nanoTime()
            repeat(1000) { assertEquals(expected, encode(body)) }
            return JSONObject().put("iterations", 1000)
                .put("allocatedBytes", Debug.getRuntimeStat("art.gc.bytes-allocated").toLong() - allocatedBefore)
                .put("elapsedMs", (System.nanoTime() - started) / 1_000_000.0)
        }
        val reference = measure(::compact)
        val actual = measure(EvidencePolicy::bodyDigest)
        val report = JSONObject().put("reference", reference).put("production", actual)
            .put("scope", "isolated_art_digest_allocation_not_process_peak")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        File(context.filesDir, "acceptance-a4-digest-resource.json").writeText(report.toString(2))
        assertTrue("production digest allocation exceeds equivalent encoder by over 50%",
            actual.getLong("allocatedBytes") <= reference.getLong("allocatedBytes") * 1.5)
    }
}
