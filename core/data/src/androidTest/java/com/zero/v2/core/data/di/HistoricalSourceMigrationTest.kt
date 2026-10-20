package com.zero.v2.core.data.di

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.zero.v2.core.data.db.ZeroDatabase
import com.zero.v2.core.data.entity.*
import com.zero.v2.core.data.store.HistoricalSourceMigration
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class HistoricalSourceMigrationTest {
    @Test fun explicitImportKeepsDateAndIsIdempotent() = runBlocking<Unit> {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), ZeroDatabase::class.java).build()
        try {
            db.conversationDao().insert(ConversationEntity("history", "synthetic"))
            val m = MessageEntity("old", "history", "user", "WebView 原生页面自己渲染", createdAt = 100, source = "CHAT_WINDOW")
            db.messageDao().insert(m)
            val migration = HistoricalSourceMigration(db)
            assertEquals(listOf(m), migration.search("webview 渲染"))
            assertTrue(db.chronicleSourceDao().all().isEmpty())
            assertTrue(migration.retain(m)); assertFalse(migration.retain(m))
            assertEquals(100L, db.chronicleSourceDao().all().single().recordedAt)
            assertEquals(m, db.chronicleSourceDao().historicalMessage(m.id))
            assertEquals(0, db.memoryDao().count())
        } finally { db.close() }
    }
    @Test fun deletedSelectionCannotBeImportedLate() = runBlocking<Unit> {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), ZeroDatabase::class.java).build()
        try {
            db.conversationDao().insert(ConversationEntity("history", "synthetic"))
            val m = MessageEntity("old", "history", "user", "历史来源", source = "VOICE")
            db.messageDao().insert(m)
            db.chronicleSourceDao().redact(ChronicleDeletionEntity("transcript", m.id, 1))
            assertTrue(HistoricalSourceMigration(db).search("历史").isEmpty())
            assertTrue(runCatching { HistoricalSourceMigration(db).retain(m) }.isFailure)
            assertTrue(db.chronicleSourceDao().all().isEmpty())
        } finally { db.close() }
    }
    @Test fun automatedAndChangedSelectionsCannotAcquireUserProvenance() = runBlocking<Unit> {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), ZeroDatabase::class.java).build()
        try {
            db.conversationDao().insert(ConversationEntity("history", "synthetic"))
            val automated = MessageEntity("auto", "history", "user", "旧方案", source = "CHAT_WINDOW", automationRunId = "run")
            db.messageDao().insert(automated)
            val migration = HistoricalSourceMigration(db)
            assertTrue(migration.search("旧方案").isEmpty())
            assertTrue(runCatching { migration.retain(automated) }.isFailure)
            val selected = automated.copy(id = "user", automationRunId = null)
            db.messageDao().insert(selected)
            assertEquals(listOf(selected), migration.search("旧方案"))
            db.messageDao().insert(selected.copy(content = "后来修订"))
            assertTrue(runCatching { migration.retain(selected) }.isFailure)
            assertTrue(db.chronicleSourceDao().all().isEmpty())
        } finally { db.close() }
    }

}

