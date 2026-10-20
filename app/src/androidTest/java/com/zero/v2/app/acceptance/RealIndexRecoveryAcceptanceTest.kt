package com.zero.v2.app.acceptance

import android.content.ContextWrapper
import android.net.ConnectivityManager
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.zero.v2.core.data.db.ZeroDatabase
import com.zero.v2.core.data.entity.KbChunkEntity
import com.zero.v2.core.data.entity.KbDocEntity
import com.zero.v2.core.data.entity.MemoryEntity
import com.zero.v2.services.embedding.*
import dagger.hilt.android.EntryPointAccessors
import java.io.File
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Separate invocations reopen this dedicated database; all rows are synthetic. */
@RunWith(AndroidJUnit4::class)
class RealIndexRecoveryAcceptanceTest {
    @Test fun recoveryStage() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        check(args.getString("allowRealProvider") == "true")
        val stage = args.getString("stage") ?: error("stage required")
        require(stage in setOf("seed", "offline", "recover", "switch", "cleanup"))
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val databaseName = "acceptance-vector-recovery.db"
        val root = File(context.cacheDir, "acceptance-vector-recovery")
        if (stage == "cleanup") {
            context.deleteDatabase(databaseName)
            root.deleteRecursively()
            return@runBlocking
        }
        val entry = EntryPointAccessors.fromApplication(context, ProviderAcceptanceEntryPoint::class.java)
        entry.chatProfiles().refreshProfiles()
        val selected = entry.embeddingProvider()
        val provider = if (stage == "switch") entry.embeddingCandidates().create(
            if (selected.modelId == "text-embedding-v4") "text-embedding-v3" else "text-embedding-v4") else selected
        val db = Room.databaseBuilder(context, ZeroDatabase::class.java, databaseName).build()
        val isolatedContext = object : ContextWrapper(context) {
            override fun getNoBackupFilesDir() = root
        }
        val worker = VectorIndexWorker(db.vectorIndexDao(), provider,
            VectorEvaluationJournal(isolatedContext), VectorBenchmarkGate(provider, entry.semanticReranker()))
        val report = JSONObject().put("stage", stage).put("personalData", false).put("completed", false)
        val output = File(context.filesDir, "acceptance-recovery-$stage.json")
        output.writeText(report.toString())
        try {
            if (stage == "seed") {
                assertEquals(0, db.vectorIndexDao().sourceCount("memory"))
                db.memoryDao().insert(MemoryEntity("seed", "独立验收资料：保留用户数据，失败时保留原有索引。"))
                db.kbDao().insertDoc(KbDocEntity("seed", "seed.md", "独立验收", 0, 0, 1, 0))
                db.kbDao().insertChunks(listOf(KbChunkEntity("seed", "seed", 0,
                    text = "独立验收资料：保留用户数据，失败时保留原有索引。", charLen = 30, updatedAt = 0)))
            }
            if (stage == "offline") {
                val connectivity = context.getSystemService(ConnectivityManager::class.java)
                assertNull("Device must actually be offline", connectivity.activeNetwork)
            }
            val domains = JSONObject()
            report.put("domains", domains)
            for (domain in VectorDomain.entries) {
                val before = db.vectorIndexDao().active(domain.key)
                val result = worker.build(domain)
                val after = db.vectorIndexDao().active(domain.key)
                val row = JSONObject().put("outcome", result.name)
                    .put("activePreserved", before?.id == after?.id)
                    .put("spaceChanged", before != null && before.space != after?.space)
                    .put("report", worker.evaluationReport(domain)?.let { JSONObject(it) } ?: JSONObject.NULL)
                domains.put(domain.key, row)
                output.writeText(report.toString())
                if (stage == "offline") {
                    assertEquals(VectorIndexWorker.Outcome.RETRY, result)
                    assertNotNull(before)
                    assertEquals(before!!.id, after!!.id)
                    assertTrue(worker.retryAt(domain) != Long.MAX_VALUE)
                    assertNotNull(db.vectorIndexDao().publishedEntry(before.id, "seed"))
                } else {
                    assertEquals(VectorIndexWorker.Outcome.CURRENT, result)
                    assertNotNull(after)
                    if (stage == "recover" || stage == "switch") assertNotEquals(before!!.id, after!!.id)
                    if (stage == "switch") assertNotEquals(before!!.space, after!!.space)
                    assertEquals(if (stage == "seed") 1 else 2, db.vectorIndexDao().matchingCount(after!!.id))
                }
            }
            if (stage == "seed") {
                db.memoryDao().insert(MemoryEntity("added", "独立验收新增资料：网络恢复后继续构建新候选。"))
                db.kbDao().insertDoc(KbDocEntity("added", "added.md", "新增验收", 0, 0, 1, 0))
                db.kbDao().insertChunks(listOf(KbChunkEntity("added", "added", 0,
                    text = "独立验收新增资料：网络恢复后继续构建新候选。", charLen = 28, updatedAt = 0)))
            }
            report.put("completed", true)
            output.writeText(report.toString())
        } finally { db.close() }
    }
}
