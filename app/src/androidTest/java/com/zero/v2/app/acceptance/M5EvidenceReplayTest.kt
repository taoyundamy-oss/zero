package com.zero.v2.app.acceptance

import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class M5EvidenceReplayTest {
    @Test fun sameCapturedChunksRetainContentWithBoundedWrites() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val original = File(context.cacheDir, "m5-main-v1.json").readText()
        val root = File(context.cacheDir, "m5-replay-${UUID.randomUUID()}").apply { mkdirs() }
        val rows = JSONArray()
        var finalText: String? = null
        try {
            for (batched in listOf(false, true)) {
                val report = JSONObject(original)
                val requests = report.getJSONArray("requests")
                val row = requests.getJSONObject(requests.length() - 1)
                val captured = row.getJSONArray("chunks")
                val sample = minOf(64, captured.length()); check(sample > 0)
                val chunks = JSONArray(); row.put("chunks", chunks)
                val output = File(root, if (batched) "batched.json" else "chunkwise.json")
                val writer = M5EvidenceWriter(output, now = { 1L })
                val start = System.nanoTime()
                repeat(sample) { i -> chunks.put(captured.get(i)); writer.write(report, force = !batched) }
                writer.write(report) // Completion/failure is always flushed.
                val text = output.readText()
                if (finalText == null) finalText = text else assertEquals(finalText, text)
                assertEquals(sample, JSONObject(text).getJSONArray("requests").getJSONObject(requests.length() - 1)
                    .getJSONArray("chunks").length())
                assertEquals(if (batched) 2 else sample + 1, writer.writes)
                rows.put(JSONObject().put("batched", batched).put("sampleChunks", sample)
                    .put("writes", writer.writes).put("bytesWritten", writer.bytes)
                    .put("elapsedMs", (System.nanoTime() - start) / 1_000_000))
            }
            File(context.cacheDir, "m5-evidence-replay.json").writeText(JSONObject().put("rows", rows)
                .put("sameFinalPayload", true).put("cloudCalls", 0).put("timingScope", "offline_replay_not_model_latency")
                .put("payloadSha256", MessageDigest.getInstance("SHA-256").digest(finalText!!.toByteArray())
                    .joinToString("") { "%02x".format(it) }).toString(2))
        } finally { root.deleteRecursively() }
    }
}
