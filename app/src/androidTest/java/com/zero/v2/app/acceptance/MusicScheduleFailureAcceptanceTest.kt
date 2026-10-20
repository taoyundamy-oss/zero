package com.zero.v2.app.acceptance

import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.platform.app.InstrumentationRegistry
import com.zero.v2.core.data.db.ZeroDatabase
import com.zero.v2.core.data.entity.PlaylistEntity
import com.zero.v2.core.data.entity.ScheduledPlaybackEntity
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Actual scheduler engine, in-memory SQLite and no playable files/cloud calls. */
class MusicScheduleFailureAcceptanceTest {
    @Test fun deletedPlaylistDoesNotBecomeSuccessfulFire() = verify(false)
    @Test fun emptyPlaylistDoesNotBecomeSuccessfulFire() = verify(true)

    @Test fun completionCannotOverwriteCancellation() = cancellation(false)
    @Test fun recurringCompletionCannotReviveCancellation() = cancellation(true)

    @Test fun overdueRecurringRecoverySkipsOldPeriodsWithoutPlayback() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val entry = EntryPointAccessors.fromApplication(context, RegenerationAcceptanceEntryPoint::class.java)
        val production = EntryPointAccessors.fromApplication(context, ProviderAcceptanceEntryPoint::class.java)
        assertTrue("Do not alter a personal music alarm", production.database().musicDao().loadSchedules().none { it.status in setOf("active", "firing") })
        val db = Room.inMemoryDatabaseBuilder(context, ZeroDatabase::class.java).build()
        val module = entry.musicAcceptanceFactory().create(context, db)
        val initial = System.currentTimeMillis() - 120000
        try {
            db.withTransaction { db.musicDao().insertSchedule(ScheduledPlaybackEntity("synthetic-recurring", initial,
                isRecurring = true, recurringMs = 60000)) }
            assertNotNull(entry.lifecycleManager().safeCall(module,"acceptance-awake") { module.onAwake() })
            assertNotNull(entry.lifecycleManager().safeCall(module,"acceptance-start") { module.onStart() })
            kotlinx.coroutines.withTimeout(5000) {
                while (db.musicDao().getScheduleById("synthetic-recurring")!!.triggerAt <= System.currentTimeMillis()) kotlinx.coroutines.delay(25)
                while (db.agentTraceEventDao().getByTraceId("music").none { it.summary.startsWith("alarm_rescheduled:") }) kotlinx.coroutines.delay(25)
            }
            val restored = db.musicDao().getScheduleById("synthetic-recurring")!!
            assertEquals("active",restored.status)
            assertEquals(0L,(restored.triggerAt-initial)%60000)
            assertTrue("Recovery must skip overdue recurring periods without attempting playback",
                db.agentTraceEventDao().getByTraceId("music").none { it.summary.startsWith("music_wake: due=") || it.summary.startsWith("schedule_playback_failed:") })
        } finally {
            entry.lifecycleManager().safeCall(module,"acceptance-destroy") { module.onDestroy() }
            com.zero.v2.services.agent.ExactAlarmScheduler(context).cancelMusic()
            db.close()
        }
    }

    private fun cancellation(recurring: Boolean) = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context, ZeroDatabase::class.java).build()
        try {
            db.withTransaction { db.musicDao().insertSchedule(ScheduledPlaybackEntity(id = "synthetic", triggerAt = 0,
                status = ScheduledPlaybackEntity.STATUS_FIRING, isRecurring = recurring, recurringMs = 60000)) }
            assertEquals(1, db.musicDao().cancelSchedule("synthetic"))
            if (recurring) db.musicDao().advanceRecurring("synthetic", 60000) else db.musicDao().markFired("synthetic")
            db.musicDao().markFailed("synthetic")
            db.musicDao().markSkippedCap("synthetic")
            assertEquals("Completion must preserve an accepted cancellation", "cancelled", db.musicDao().getScheduleById("synthetic")!!.status)
        } finally { db.close() }
    }

    private fun verify(emptyPlaylist: Boolean) = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val entry = EntryPointAccessors.fromApplication(context, RegenerationAcceptanceEntryPoint::class.java)
        val db = Room.inMemoryDatabaseBuilder(context, ZeroDatabase::class.java).build()
        val module = entry.musicAcceptanceFactory().create(context, db)
        try {
            val production = EntryPointAccessors.fromApplication(context, ProviderAcceptanceEntryPoint::class.java)
            org.junit.Assert.assertTrue("Isolated executor must not cancel an existing personal music alarm",
                production.database().musicDao().loadSchedules().none { it.status in setOf("active", "firing") })
            db.withTransaction {
                if (emptyPlaylist) db.musicDao().insertPlaylist(PlaylistEntity(id = "synthetic-playlist", name = "Synthetic", createdBy = "USER", createdAt = 0))
                db.musicDao().insertSchedule(ScheduledPlaybackEntity(id = "synthetic-schedule", triggerAt = 0,
                    mode = ScheduledPlaybackEntity.MODE_FIXED, playlistId = if (emptyPlaylist) "synthetic-playlist" else null,
                    createdBy = "USER"))
            }
            assertNotNull(entry.lifecycleManager().safeCall(module, "acceptance-awake") { module.onAwake() })
            assertNotNull(entry.lifecycleManager().safeCall(module, "acceptance-execute") { module.execute() })
            assertEquals("A rejected fixed playback must be visible as failed, never fired", "failed",
                db.musicDao().getScheduleById("synthetic-schedule")!!.status)
            kotlinx.coroutines.withTimeout(2000) {
                while (db.agentTraceEventDao().getByTraceId("music").none {
                    it.summary.startsWith("schedule_playback_failed: synthetic-schedule") && it.nodeType == "ERROR" && !it.detail.isNullOrBlank()
                }) kotlinx.coroutines.delay(25)
            }
        } finally {
            entry.lifecycleManager().safeCall(module, "acceptance-destroy") { module.onDestroy() }
            db.close()
        }
    }
}
