package com.zero.v2.core.data.di

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

internal object MemoryDerivationMigration : Migration(36, 37) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS memory_derivations (id TEXT NOT NULL PRIMARY KEY, " +
            "sourceId TEXT NOT NULL, slotId TEXT NOT NULL, content TEXT NOT NULL, quote TEXT NOT NULL, type TEXT NOT NULL, " +
            "basis TEXT NOT NULL, scope TEXT NOT NULL, claimKey TEXT NOT NULL, importance REAL NOT NULL, " +
            "occurredAt INTEGER, validFrom INTEGER, validUntil INTEGER, createdAt INTEGER NOT NULL, " +
            "state TEXT NOT NULL, appliedAt INTEGER, FOREIGN KEY(sourceId) REFERENCES chronicle_sources(id) " +
            "ON UPDATE NO ACTION ON DELETE CASCADE)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_memory_derivations_sourceId ON memory_derivations(sourceId)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_memory_derivations_slotId ON memory_derivations(slotId)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_memory_derivations_state_createdAt ON memory_derivations(state,createdAt)")
        db.execSQL("CREATE TABLE IF NOT EXISTS memory_derivation_views (slotId TEXT NOT NULL PRIMARY KEY, scope TEXT NOT NULL, " +
            "state TEXT NOT NULL, selectedId TEXT, eligibleCount INTEGER NOT NULL, version TEXT NOT NULL, rebuiltAt INTEGER NOT NULL)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_memory_derivation_views_scope ON memory_derivation_views(scope)")
        MemoryDerivationPrivacy.install(db)
    }
}

internal object MemoryDerivationPrivacy {
    fun install(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE TRIGGER IF NOT EXISTS derivation_view_delete AFTER DELETE ON memory_derivations BEGIN " +
            "DELETE FROM memory_derivation_views WHERE slotId = OLD.slotId; END")
        db.execSQL("CREATE TRIGGER IF NOT EXISTS derivation_memory_forget AFTER INSERT ON memory_deletions BEGIN " +
            "INSERT OR IGNORE INTO chronicle_deletions(kind,anchorId,deletedAt) " +
            "SELECT s.kind,s.anchorId,NEW.deletedAt FROM memory_derivations d JOIN chronicle_sources s ON s.id = d.sourceId " +
            "WHERE 'derived:' || d.id = NEW.id; END")
    }
}
