package com.zero.v2.core.data.di

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** Additive only: no legacy memories, settings or usage rows are rewritten. */
internal object MemoryReviewMigration : Migration(34, 35) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS memory_reviews (id TEXT NOT NULL PRIMARY KEY, " +
            "memoryId TEXT NOT NULL, baseVersion TEXT NOT NULL, proposedContent TEXT NOT NULL, " +
            "proposedType TEXT NOT NULL, proposedScope TEXT NOT NULL, reason TEXT NOT NULL, " +
            "sourceMessageId TEXT, sourceVersion TEXT, state TEXT NOT NULL, evidenceKind TEXT NOT NULL, " +
            "createdAt INTEGER NOT NULL, decidedAt INTEGER, resultMemoryId TEXT, " +
            "FOREIGN KEY(memoryId) REFERENCES memories(id) ON UPDATE NO ACTION ON DELETE CASCADE)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_memory_reviews_resultMemoryId ON memory_reviews(resultMemoryId)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_memory_reviews_memoryId ON memory_reviews(memoryId)")
    }
}
