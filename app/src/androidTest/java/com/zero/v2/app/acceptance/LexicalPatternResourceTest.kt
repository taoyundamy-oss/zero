package com.zero.v2.app.acceptance

import android.os.Debug
import androidx.test.platform.app.InstrumentationRegistry
import com.zero.v2.services.retrieval.RetrievalKit
import java.io.File
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Compare the production bigram seam with an equivalent reusable pattern, using synthetic text. */
class LexicalPatternResourceTest {
    @Test fun queryPatternAllocationMustStayNearEquivalentReuse() {
        val query = "邵阳 天气，用户更正后在哪里培训？"
        val pattern = Regex("[\\s，。、？?！!的了吗呢]")
        fun reference(text: String) = pattern.replace(text, "").windowed(2).distinct().take(8)
        val expected = reference(query)
        fun measure(read: (String) -> List<String>): JSONObject {
            repeat(50) { assertEquals(expected, read(query)) }
            val allocatedBefore = Debug.getRuntimeStat("art.gc.bytes-allocated").toLong()
            var nativePeak = Debug.getNativeHeapAllocatedSize()
            val started = System.nanoTime()
            repeat(2000) {
                assertEquals(expected, read(query))
                if (it % 100 == 0) nativePeak = maxOf(nativePeak, Debug.getNativeHeapAllocatedSize())
            }
            val memory = Debug.MemoryInfo(); Debug.getMemoryInfo(memory)
            return JSONObject().put("iterations", 2000).put("allocatedBytes",
                Debug.getRuntimeStat("art.gc.bytes-allocated").toLong() - allocatedBefore)
                .put("elapsedMs", (System.nanoTime() - started) / 1_000_000.0)
                .put("sampledNativeAllocatedBytes", nativePeak).put("pssKb", memory.totalPss)
        }
        val reused = measure(::reference)
        val actual = measure(RetrievalKit::queryBigrams)
        File(InstrumentationRegistry.getInstrumentation().targetContext.filesDir,
            "acceptance-a4-pattern-resource.json").writeText(JSONObject().put("reference", reused)
                .put("production", actual).put("scope", "synthetic_bigram_allocation_not_native_ownership").toString(2))
        assertTrue("production bigram allocation exceeds reusable equivalent by over 50%",
            actual.getLong("allocatedBytes") <= reused.getLong("allocatedBytes") * 1.5)
        assertTrue("production bigram native allocated peak exceeds reusable equivalent by over 50%",
            actual.getLong("sampledNativeAllocatedBytes") <= reused.getLong("sampledNativeAllocatedBytes") * 1.5)
    }
}
