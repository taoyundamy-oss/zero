package com.zero.v2.app.acceptance

import android.content.ContextWrapper
import android.os.Debug
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import com.zero.v2.services.embedding.LocalBgeProvider
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Explicit local-only candidate inference; package is staged in isolated cache by the host. */
class LocalBgeAcceptanceTest {
    @Test fun pinnedCandidateRunsOnDeviceAndMatchesDesktopReference() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(context.cacheDir, "p7-bge")
        val model = File(root, "local-embedding/bge-small-zh-v1.5")
        assertTrue("Host must stage the verified candidate first", File(model,"manifest.json").isFile)
        val scoped = object : ContextWrapper(context) { override fun getFilesDir() = root }
        val provider = LocalBgeProvider(scoped)
        assertTrue(provider.isAvailable())
        val golden = JSONArray(File(root,"vectors.json").readText())
        val results = JSONArray()
        for (i in 0 until golden.length()) {
            val fixture = golden.getJSONObject(i)
            val started = SystemClock.elapsedRealtime()
            val actual = requireNotNull(provider.embed(fixture.getString("text"))) { "Local ORT returned null" }
            val elapsed = SystemClock.elapsedRealtime() - started
            val expected = fixture.getJSONArray("vector")
            assertEquals(512, actual.size)
            val maxError = actual.indices.maxOf { kotlin.math.abs(actual[it] - expected.getDouble(it)) }
            assertTrue("ONNX parity error=$maxError", maxError < 0.0001)
            results.put(JSONObject().put("case",i).put("latencyMs",elapsed).put("maxError",maxError))
        }
        val memory = Debug.MemoryInfo().also(Debug::getMemoryInfo)
        File(context.cacheDir,"p7-bge-report.json").writeText(JSONObject()
            .put("cases",results).put("totalPssKb",memory.totalPss).put("nativeHeapBytes",Debug.getNativeHeapAllocatedSize())
            .put("defaultBackendChanged",false).put("cloudRequests",0).toString(2))
    }
}
