package com.zero.v2.app.acceptance

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith

/** Aggregate-only read audit: never exports text or edits personal records. */
@RunWith(AndroidJUnit4::class)
class MemoryHistoryAuditTest {
    @Test fun aggregateSourceAudit() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = EntryPointAccessors.fromApplication(context, ProviderAcceptanceEntryPoint::class.java).database()
        val report = withContext(Dispatchers.IO) {
            fun count(sql: String): Long = db.openHelper.readableDatabase.query(sql).use {
                check(it.moveToFirst()); it.getLong(0)
            }
            JSONObject().put("scope", "aggregate_read_only_not_semantic_validation")
                .put("memories", count("SELECT COUNT(*) FROM memories"))
                .put("unsourcedFacts", count("SELECT COUNT(*) FROM memories WHERE type='FACT' AND (source_conversation_id IS NULL OR source_conversation_id='')"))
                .put("unsourcedFactsExactUserQuote", count("SELECT COUNT(*) FROM memories m WHERE m.type='FACT' AND (m.source_conversation_id IS NULL OR m.source_conversation_id='') AND length(trim(m.content))>=8 AND EXISTS(SELECT 1 FROM messages u WHERE lower(u.role)='user' AND u.system_source IS NULL AND u.trigger_id IS NULL AND (u.source IS NULL OR u.source IN ('CHAT_WINDOW','VOICE','REMOTE')) AND instr(u.content,m.content)>0)"))
                .put("legacyAttributes", count("SELECT COUNT(*) FROM user_graph_attributes WHERE evidence IS NULL OR evidence NOT LIKE 'memory:v1:%'"))
                .put("legacyRelationships", count("SELECT COUNT(*) FROM user_graph_relationships WHERE evidence IS NULL OR evidence NOT LIKE 'memory:v1:%'"))
                .put("orphanEntities", count("SELECT COUNT(*) FROM user_graph_entities e WHERE NOT EXISTS (SELECT 1 FROM user_graph_attributes a WHERE a.entityId=e.id) AND NOT EXISTS (SELECT 1 FROM user_graph_relationships r WHERE r.fromEntityId=e.id OR r.toEntityId=e.id)"))
                .put("templateCandidates", count("SELECT COUNT(*) FROM memories WHERE type='FACT' AND (content LIKE '%随时%打断%' OR content LIKE '%提醒%微信%')"))
        }
        java.io.File(context.filesDir, "acceptance-a3-history.json").writeText(report.toString(2))
        InstrumentationRegistry.getInstrumentation().sendStatus(0, android.os.Bundle().apply { putString("aggregate_report", report.toString()) })
    }
}
