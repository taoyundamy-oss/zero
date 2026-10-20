package com.zero.v2.app.acceptance

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.zero.v2.core.data.db.ZeroDatabase
import com.zero.v2.core.data.entity.ScheduledPlaybackEntity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** Isolated SQLite; active or claimed work must survive UI terminal-history cleanup. */
class MusicScheduleCleanupAcceptanceTest {
    @Test fun cleanupRefusesActiveFiringAndUnknownStatus() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(InstrumentationRegistry.getInstrumentation().targetContext, ZeroDatabase::class.java).build()
        try {
            val dao = db.musicDao()
            for (status in listOf("active","firing","unknown_future_state")) {
                dao.insertSchedule(ScheduledPlaybackEntity(status, 0, status = status))
                dao.deleteSchedule(status)
                assertTrue("Live or unknown scheduled work must survive cleanup", dao.loadSchedules().any { it.id == status })
            }
        } finally { db.close() }
    }
    @Test fun cleanupDeletesOnlyTerminalHistory() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(InstrumentationRegistry.getInstrumentation().targetContext, ZeroDatabase::class.java).build()
        try {
            val dao = db.musicDao()
            for (status in listOf("fired","failed","missed","skipped_cap","cancelled")) {
                dao.insertSchedule(ScheduledPlaybackEntity(status, 0, status = status))
                dao.deleteSchedule(status)
                assertFalse(dao.loadSchedules().any { it.id == status })
            }
        } finally { db.close() }
    }
}
