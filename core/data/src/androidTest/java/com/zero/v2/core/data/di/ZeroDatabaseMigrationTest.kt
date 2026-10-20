package com.zero.v2.core.data.di

import android.database.Cursor
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import com.zero.v2.core.data.db.ZeroDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ZeroDatabaseMigrationTest {

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        ZeroDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory()
    )

    @Test
    fun migrate5To31_preservesExistingRowsAndMatchesSchema() {
        helper.createDatabase(DATABASE_NAME, 5).apply {
            execSQL(
                """INSERT INTO memories (
                    id, content, embedding, level, importance, source_conversation_id,
                    keywords, tier, created_at, access_count, last_accessed_at
                ) VALUES ('memory-5', 'legacy memory', NULL, 1, 0.8, NULL,
                    'legacy', 2, 1000, 3, 2000)""".trimIndent()
            )
            execSQL(
                """INSERT INTO conversations (
                    id, title, character_id, created_at, updated_at, message_count
                ) VALUES ('conversation-5', 'legacy conversation', NULL, 1000, 2000, 1)""".trimIndent()
            )
            execSQL(
                """INSERT INTO messages (
                    id, conversation_id, role, content, tool_calls, automation_run_id,
                    tokens_used, attachments, created_at
                ) VALUES ('message-5', 'conversation-5', 'USER', 'legacy message',
                    NULL, NULL, 12, NULL, 2000)""".trimIndent()
            )
            close()
        }

        helper.runMigrationsAndValidate(
            DATABASE_NAME,
            31,
            true,
            *DatabaseModule.ALL_MIGRATIONS
        ).apply {
            query("SELECT content, type, status, confidence, reinforcement_count FROM memories WHERE id = 'memory-5'")
                .use { cursor ->
                    cursor.requireSingleRow()
                    assertEquals("legacy memory", cursor.getString(0))
                    assertEquals("FACT", cursor.getString(1))
                    assertEquals("ACTIVE", cursor.getString(2))
                    assertEquals(0.5f, cursor.getFloat(3))
                    assertEquals(0, cursor.getInt(4))
                }
            // R2：memories.embed_model 列存在且默认 ''（老库升级零数据丢失，旧向量标记未知模型）
            query("SELECT embed_model FROM memories WHERE id = 'memory-5'")
                .use { cursor -> cursor.requireSingleRow(); assertEquals("", cursor.getString(0)) }
            query("SELECT content, source, system_source, trigger_id FROM messages WHERE id = 'message-5'")
                .use { cursor ->
                    cursor.requireSingleRow()
                    assertEquals("legacy message", cursor.getString(0))
                    assertEquals(true, cursor.isNull(1))
                    assertEquals(true, cursor.isNull(2))
                    assertEquals(true, cursor.isNull(3))
                }
            query("SELECT callId, functionName FROM agent_trace_events LIMIT 1")
                .use { cursor -> assertEquals(false, cursor.moveToFirst()) }
            // R1：memory_extraction_jobs 表存在且空（新建表，老库零数据丢失）
            query("SELECT COUNT(*) FROM memory_extraction_jobs")
                .use { cursor -> cursor.requireSingleRow(); assertEquals(0, cursor.getInt(0)) }
            close()
        }
    }

    @Test
    fun migrate18To31_matchesCurrentSchema() = migrateRecentVersion(18)

    @Test
    fun migrate19To31_matchesCurrentSchema() = migrateRecentVersion(19)

    @Test
    fun migrate20To31_matchesCurrentSchema() = migrateRecentVersion(20)

    @Test
    fun migrate26To31_matchesCurrentSchema() = migrateRecentVersion(26)

    @Test
    fun migrate26To31_preservesKnowledgeTextAndLegacyVectors() {
        helper.createDatabase(DATABASE_NAME, 26).apply {
            execSQL("INSERT INTO kb_docs(id, relative_path, title, mtime, size, chunk_count, indexed_at, tags) " +
                "VALUES('doc26', 'legacy.md', '旧知识', 1, 2, 1, 3, '')")
            execSQL("INSERT INTO kb_chunks(id, doc_id, seq, heading_path, text, char_len, embedding, embed_model, updated_at) " +
                "VALUES('chunk26', 'doc26', 0, '', '正文', 2, X'0000803F', 'legacy-model', 3)")
            close()
        }
        helper.runMigrationsAndValidate(DATABASE_NAME, 31, true, *DatabaseModule.ALL_MIGRATIONS).apply {
            query("SELECT text, embedding, embed_model FROM kb_chunks WHERE id = 'chunk26'").use {
                it.requireSingleRow()
                assertEquals("正文", it.getString(0))
                org.junit.Assert.assertArrayEquals(byteArrayOf(0, 0, 0x80.toByte(), 0x3f), it.getBlob(1))
                assertEquals("legacy-model", it.getString(2))
            }
            query("SELECT COUNT(*) FROM vector_active").use {
                it.requireSingleRow(); assertEquals(0, it.getInt(0))
            }
            close()
        }
    }

    private fun migrateRecentVersion(startVersion: Int) {
        helper.createDatabase(DATABASE_NAME, startVersion).close()
        helper.runMigrationsAndValidate(
            DATABASE_NAME,
            31,
            true,
            *DatabaseModule.ALL_MIGRATIONS
        ).close()
    }

    @Test
    fun migrate28To31_matchesCurrentSchema() = migrateRecentVersion(28)

    @Test
    fun migrate29To31_preservesLegacyEntityOwnership() {
        helper.createDatabase(DATABASE_NAME, 29).apply {
            execSQL("INSERT INTO user_graph_entities (id,name,type,createdAt) VALUES ('legacy','历史实体','CONCEPT',1)")
            close()
        }
        helper.runMigrationsAndValidate(DATABASE_NAME, 31, true, *DatabaseModule.ALL_MIGRATIONS).apply {
            query("SELECT name,memoryDerived FROM user_graph_entities WHERE id='legacy'").use {
                assertEquals(true, it.moveToFirst())
                assertEquals("历史实体", it.getString(0))
                assertEquals(0, it.getInt(1))
            }
            close()
        }
    }

    @Test
    fun migrate27To31_preservesLegacyGraphWithoutInventingEvidence() {
        helper.createDatabase(DATABASE_NAME, 27).apply {
            execSQL("INSERT INTO user_graph_relationships(fromEntityId,toEntityId,type,effectiveFrom,effectiveTo) " +
                "VALUES('user','person_old','colleague',1,NULL)")
            execSQL("INSERT INTO user_graph_relationships(fromEntityId,toEntityId,type,effectiveFrom,effectiveTo) " +
                "VALUES('user','person_old','colleague',0,1)")
            close()
        }
        helper.runMigrationsAndValidate(DATABASE_NAME, 31, true, *DatabaseModule.ALL_MIGRATIONS).apply {
            query("SELECT effectiveFrom,effectiveTo,evidence FROM user_graph_relationships ORDER BY effectiveFrom").use {
                assertEquals(2, it.count)
                assertEquals(true, it.moveToFirst())
                assertEquals(0L, it.getLong(0))
                assertEquals(1L, it.getLong(1))
                assertEquals(true, it.isNull(2))
                assertEquals(true, it.moveToNext())
                assertEquals(1L, it.getLong(0))
                assertEquals(true, it.isNull(1))
                assertEquals(true, it.isNull(2))
            }
            close()
        }
    }

    @Test
    fun migrate30To31_addsLedgerAndTerminalIsIdempotent() {
        helper.createDatabase(DATABASE_NAME, 30).close()
        helper.runMigrationsAndValidate(DATABASE_NAME, 31, true, *DatabaseModule.ALL_MIGRATIONS).apply {
            execSQL("INSERT INTO usage_requests(id,startedAt,connectionId,profileId,model,kind,status,continuity,budgetSource,local) " +
                "VALUES('one',1,'c','p','m','stream','prepared','unknown','unknown',0)")
            execSQL("UPDATE usage_requests SET status='completed',inputTokens=100,cachedTokens=0 WHERE id='one' AND status='prepared'")
            execSQL("UPDATE usage_requests SET status='failed',inputTokens=NULL WHERE id='one' AND status='prepared'")
            query("SELECT status,inputTokens,cachedTokens FROM usage_requests WHERE id='one'").use {
                it.requireSingleRow()
                assertEquals("completed", it.getString(0))
                assertEquals(100L, it.getLong(1))
                assertEquals(0L, it.getLong(2))
            }
            close()
        }
    }

    @Test
    fun migrate31To34_preservesLedgerAndLeavesLegacyProvenanceUnknown() {
        helper.createDatabase(DATABASE_NAME, 31).apply {
            execSQL("INSERT INTO usage_requests(id,startedAt,connectionId,profileId,model,kind,status,continuity,budgetSource,local,inputTokens,cachedTokens) " +
                "VALUES('preserve',1,'c','p','m','stream','completed','append_only','observed',0,100,98)")
            execSQL("INSERT INTO memories(id,content,level,importance,tier,type,status,confidence,reinforcement_count,created_at,access_count,last_accessed_at,embed_model) " +
                "VALUES('legacy','原有内容',0,0.8,2,'FACT','ACTIVE',0.9,0,123,0,123,'old-space')")
            close()
        }
        helper.runMigrationsAndValidate(DATABASE_NAME, 34, true, *DatabaseModule.ALL_MIGRATIONS).apply {
            query("SELECT content,origin,source_message_id,occurred_at,revision,scope FROM memories WHERE id='legacy'").use {
                it.requireSingleRow()
                assertEquals("原有内容", it.getString(0)); assertEquals("UNKNOWN", it.getString(1))
                assertEquals(true, it.isNull(2)); assertEquals(true, it.isNull(3))
                assertEquals(1, it.getInt(4)); assertEquals("personal", it.getString(5))
            }
            query("SELECT inputTokens,cachedTokens,status FROM usage_requests WHERE id='preserve'").use {
                it.requireSingleRow(); assertEquals(100, it.getInt(0)); assertEquals(98, it.getInt(1))
                assertEquals("completed", it.getString(2))
            }
            close()
        }
    }

    @Test
    fun migrate34To35_preservesMemoryAndUsageAndStartsWithNoProposals() {
        helper.createDatabase(DATABASE_NAME, 34).apply {
            execSQL("INSERT INTO usage_requests(id,startedAt,connectionId,profileId,model,kind,status,continuity,budgetSource,local,inputTokens,cachedTokens) " +
                "VALUES('keep35',1,'c','p','m','stream','completed','append_only','observed',0,100,98)")
            execSQL("INSERT INTO memories(id,content,level,importance,tier,type,status,confidence,reinforcement_count,created_at,access_count,last_accessed_at,embed_model) " +
                "VALUES('old35','unchanged',0,0.8,2,'EVENT','ACTIVE',0.5,0,123,0,123,'old-space')")
            close()
        }
        helper.runMigrationsAndValidate(DATABASE_NAME, 35, true, *DatabaseModule.ALL_MIGRATIONS).apply {
            query("SELECT content,type,origin,revision FROM memories WHERE id='old35'").use {
                it.requireSingleRow(); assertEquals("unchanged", it.getString(0))
                assertEquals("EVENT", it.getString(1)); assertEquals("UNKNOWN", it.getString(2)); assertEquals(1, it.getInt(3))
            }
            query("SELECT inputTokens,cachedTokens FROM usage_requests WHERE id='keep35'").use {
                it.requireSingleRow(); assertEquals(100, it.getInt(0)); assertEquals(98, it.getInt(1))
            }
            query("SELECT COUNT(*) FROM memory_reviews").use { it.moveToFirst(); assertEquals(0, it.getInt(0)) }
            close()
        }
    }

    private fun Cursor.requireSingleRow() {
        assertEquals(true, moveToFirst())
        assertEquals(1, count)
    }

    private companion object {
        const val DATABASE_NAME = "zero-migration-test"
    }
}

