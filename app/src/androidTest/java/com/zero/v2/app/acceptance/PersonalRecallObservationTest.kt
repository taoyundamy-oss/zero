package com.zero.v2.app.acceptance

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.zero.v2.core.common.MemoryStatus
import com.zero.v2.core.moduleapi.IZeroModule
import com.zero.v2.core.moduleapi.ToolContributor
import dagger.hilt.android.EntryPointAccessors
import java.io.File
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Observations only: user questions without labelled answers cannot yield a quality pass. */
@RunWith(AndroidJUnit4::class)
class PersonalRecallObservationTest {
    @Test fun readRecentDiagnostics() = runBlocking {
        check(InstrumentationRegistry.getArguments().getString("allowPersonalRecall") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val entry = EntryPointAccessors.fromApplication(context, ProviderAcceptanceEntryPoint::class.java)
        val db = entry.database()
        val rows = db.kbDao().searchByText("白水洞", 10)
        val docs = db.kbDao().loadAllDocs().associateBy { it.id }
        val report = JSONObject().put("recentKnowledgeTraces", JSONArray(db.agentTraceEventDao().getByTraceId("kb")
            .sortedByDescending { it.timestamp }.filter { it.summary.startsWith("rerank=") }.take(8).map { it.summary }))
            .put("labelledSourceMetadata", JSONArray(rows.map { row ->
                JSONObject().put("id", row.id).put("textLength", row.text.length)
                    .put("rankingLength", "《${docs[row.docId]?.title.orEmpty()}》 ${row.headingPath} ▸ ${row.text}".length)
                    .put("headingDate", com.zero.v2.services.retrieval.RecentRecallWindow.date(row.headingPath))
            }))
        File(context.filesDir, "acceptance-recent-diagnostics.json").writeText(report.toString())
    }

    @Test fun observeUserRequestedQuestions() = runBlocking {
        check(InstrumentationRegistry.getArguments().getString("allowPersonalRecall") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val entry = EntryPointAccessors.fromApplication(context, ProviderAcceptanceEntryPoint::class.java)
        entry.chatProfiles().refreshProfiles()
        val db = entry.database()
        val memory = entry.memoryModule()
        val knowledge = entry.knowledgeModule()
        val modules: List<IZeroModule> = listOf(memory, knowledge)
        val samples = JSONArray()
        val report = JSONObject().put("scope", "personal_tool_observation")
            .put("qualityVerdict", "pending_user_labels").put("samples", samples).put("completed", false)
        val output = File(context.filesDir, "acceptance-personal-observation.json")
        val sourcesBefore = db.memoryDao().getByStatus(MemoryStatus.ACTIVE)
        report.put("activeMemories", sourcesBefore.size).put("knowledgeDocuments", db.kbDao().loadAllDocs().size)
        try {
            for (module in modules) {
                db.moduleConfigDao().getByModuleId(module.metadata.id)?.let { module.config.fromJson(it.configJson) }
                val toolName = if (module === memory) "memory_recall" else "kb_search"
                val handler = (module as ToolContributor).contributions().single { it.definition.name == toolName }.handler
                for (query in listOf("我的喜好", "我最近在做什么", "我们最近做了什么", "我们的约定")) {
                    val started = System.nanoTime()
                    val result = handler(mapOf("query" to query, "top_k" to 3))
                    samples.put(JSONObject().put("tool", toolName).put("query", query)
                        .put("success", result.success).put("result", result.data)
                        .put("latencyMs", (System.nanoTime() - started) / 1_000_000)
                        .put("matchingMemorySources", JSONArray(if (module === memory) sourcesBefore.filter {
                            result.data.contains(it.content.take(200))
                        }.map { JSONObject().put("id", it.id).put("type", it.type.name)
                            .put("confidence", it.confidence).put("hasConversationSource", it.sourceConversationId != null)
                            .put("createdAt", it.createdAt) } else emptyList<JSONObject>())))
                    output.writeText(report.toString())
                    assertTrue("Tool failed; inspect private observation report", result.success)
                }
            }
            // Normal memory recall updates access counters only; no evidence/content edits are permitted.
            val after = db.memoryDao().getByStatus(MemoryStatus.ACTIVE)
            assertTrue(sourcesBefore.associate { it.id to it.content } == after.associate { it.id to it.content })
            report.put("completed", true).put("sourceContentUnchanged", true)
                .put("retrievalDiagnostics", JSONArray(entry.traces().getTrace("mem_recall")
                    .filter { it.summary.startsWith("recall_fused:") }.map { it.summary }))
                .put("knowledgeDiagnostics", JSONArray(entry.traces().getTrace("kb")
                    .filter { it.summary.startsWith("rerank=") }.map { it.summary }))
            output.writeText(report.toString())
        } finally {
            memory.onDestroy()
            knowledge.onDestroy()
        }
    }
}
