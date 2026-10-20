package com.zero.v2.app.acceptance

import android.content.ContextWrapper
import android.os.Debug
import android.os.SystemClock
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.zero.v2.core.data.db.ZeroDatabase
import com.zero.v2.core.data.entity.KbChunkEntity
import com.zero.v2.core.data.entity.KbDocEntity
import com.zero.v2.core.data.entity.MemoryEntity
import com.zero.v2.services.embedding.*
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Real local provider and production publication gate, with synthetic sources in a separate database. */
class LocalVectorGateAcceptanceTest {
    @Test fun localCandidateUsesProductionGateInBothDomains() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(context.cacheDir, "p7-bge")
        val scoped = object : ContextWrapper(context) {
            override fun getFilesDir() = root
            override fun getNoBackupFilesDir() = File(root, "gate-journal")
        }
        val provider = LocalBgeProvider(scoped)
        assertTrue("Stage the verified candidate before running", provider.isAvailable())
        val db = Room.inMemoryDatabaseBuilder(context, ZeroDatabase::class.java).build()
        val reports = JSONArray()
        try {
            db.memoryDao().insert(MemoryEntity("isolated-local", "合成验收：完成备份后核对摘要。"))
            db.kbDao().replaceDocument(KbDocEntity("isolated-doc", "acceptance.md", "合成验收", 1, 16, 1, 1),
                listOf(KbChunkEntity("isolated-chunk", "isolated-doc", 0, "备份", "完成备份后核对摘要。", 12, updatedAt = 1)))
            val journal = VectorEvaluationJournal(scoped)
            for (domain in VectorDomain.entries) {
                val before = Debug.MemoryInfo().also(Debug::getMemoryInfo).totalPss
                val started = SystemClock.elapsedRealtime()
                val worker = VectorIndexWorker(db.vectorIndexDao(), provider, journal, VectorBenchmarkGate(provider))
                val outcome = worker.build(domain)
                val receipt = JSONObject(requireNotNull(journal.latest(domain)))
                val published = receipt.getBoolean("published")
                assertEquals(published, db.vectorIndexDao().active(domain.key) != null)
                assertEquals(1, db.vectorIndexDao().sourceCount(domain.key))
                val restarted = VectorIndexWorker(db.vectorIndexDao(), provider, journal, VectorBenchmarkGate(provider))
                assertEquals(outcome, restarted.build(domain))
                reports.put(JSONObject().put("domain", domain.key).put("outcome", outcome.name)
                    .put("elapsedMs", SystemClock.elapsedRealtime() - started)
                    .put("pssBeforeKb", before).put("pssAfterKb", Debug.MemoryInfo().also(Debug::getMemoryInfo).totalPss)
                    .put("receipt", receipt))
            }
        } finally {
            File(context.cacheDir, "p5-local-gate-report.json").writeText(JSONObject()
                .put("domains", reports).put("cloudRequests", 0).put("personalDatabaseUsed", false)
                .put("backendConfigurationChanged", false).toString(2))
            db.close()
        }
        // Preserve rejected reports, then fail acceptance rather than weakening the production gate.
        assertEquals(2, reports.length())
        for (i in 0 until reports.length()) assertTrue(reports.getJSONObject(i).getJSONObject("receipt").getBoolean("published"))
    }
}
