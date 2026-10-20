package com.zero.v2.core.data.di

import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** Additive, empty on upgrade. Never copies or certifies existing history. */
internal object ChronicleMigration : Migration(35, 36) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS chronicle_sources (id TEXT NOT NULL PRIMARY KEY, kind TEXT NOT NULL, " +
            "anchorId TEXT NOT NULL, conversationId TEXT, parentMemoryId TEXT, body TEXT NOT NULL, " +
            "provenance TEXT NOT NULL, recordedAt INTEGER NOT NULL, capturedAt INTEGER NOT NULL)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_chronicle_sources_kind_anchorId ON chronicle_sources(kind, anchorId)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_chronicle_sources_conversationId ON chronicle_sources(conversationId)")
        db.execSQL("CREATE TABLE IF NOT EXISTS chronicle_deletions (kind TEXT NOT NULL, anchorId TEXT NOT NULL, " +
            "deletedAt INTEGER NOT NULL, PRIMARY KEY(kind, anchorId))")
        ChroniclePrivacy.install(db)
    }
}

/** Also used for fresh databases: SQL-level deletes and FK cascades cannot leave raw journal copies. */
object ChroniclePrivacy : RoomDatabase.Callback() {
    override fun onCreate(db: SupportSQLiteDatabase) {
        install(db)
        MemoryDerivationPrivacy.install(db)
    }

    fun install(db: SupportSQLiteDatabase) {
        val now = "CAST(strftime('%s','now') AS INTEGER) * 1000"
        db.execSQL("CREATE TRIGGER IF NOT EXISTS chronicle_redact AFTER INSERT ON chronicle_deletions BEGIN " +
            "DELETE FROM chronicle_sources WHERE (kind = NEW.kind AND anchorId = NEW.anchorId) " +
            "OR (NEW.kind = 'transcript' AND kind = 'message' AND anchorId = NEW.anchorId) " +
            "OR (NEW.kind = 'conversation' AND conversationId = NEW.anchorId); " +
            "DELETE FROM messages WHERE NEW.kind = 'transcript' AND id = NEW.anchorId; " +
            "DELETE FROM conversations WHERE NEW.kind = 'conversation' AND id = NEW.anchorId; END")
        db.execSQL("CREATE TRIGGER IF NOT EXISTS chronicle_message_delete AFTER DELETE ON messages BEGIN " +
            "INSERT OR IGNORE INTO chronicle_deletions VALUES('message', OLD.id, $now); " +
            "INSERT OR IGNORE INTO chronicle_deletions VALUES('transcript', OLD.id, $now); END")
        db.execSQL("CREATE TRIGGER IF NOT EXISTS chronicle_conversation_delete AFTER DELETE ON conversations BEGIN " +
            "INSERT OR IGNORE INTO chronicle_deletions VALUES('conversation', OLD.id, $now); END")
        db.execSQL("CREATE TRIGGER IF NOT EXISTS chronicle_review_delete BEFORE DELETE ON memory_reviews BEGIN " +
            "INSERT OR IGNORE INTO chronicle_deletions VALUES('confirmation', OLD.id, $now); END")
        db.execSQL("CREATE TRIGGER IF NOT EXISTS chronicle_memory_forget AFTER INSERT ON memory_deletions BEGIN " +
            "INSERT OR IGNORE INTO chronicle_deletions SELECT 'message', source_message_id, NEW.deletedAt FROM memories " +
            "WHERE id = NEW.id AND source_message_id IS NOT NULL; " +
            "INSERT OR IGNORE INTO chronicle_deletions SELECT 'confirmation', anchorId, NEW.deletedAt FROM chronicle_sources " +
            "WHERE kind = 'confirmation' AND parentMemoryId = NEW.id; " +
            "INSERT OR IGNORE INTO chronicle_deletions SELECT 'confirmation', id, NEW.deletedAt FROM memory_reviews " +
            "WHERE memoryId = NEW.id OR resultMemoryId = NEW.id; END")
    }
}
