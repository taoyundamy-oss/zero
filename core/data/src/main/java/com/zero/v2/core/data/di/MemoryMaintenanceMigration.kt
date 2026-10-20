package com.zero.v2.core.data.di

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** Add nullable proposal dates only; no historic memory or existing audit is rewritten. */
internal object MemoryMaintenanceMigration : Migration(37, 38) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE memory_reviews ADD COLUMN editTimes INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE memory_reviews ADD COLUMN proposedOccurredAt INTEGER")
        db.execSQL("ALTER TABLE memory_reviews ADD COLUMN proposedValidFrom INTEGER")
        db.execSQL("ALTER TABLE memory_reviews ADD COLUMN proposedValidUntil INTEGER")
    }
}
