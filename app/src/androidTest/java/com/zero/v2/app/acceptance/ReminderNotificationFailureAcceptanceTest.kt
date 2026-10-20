package com.zero.v2.app.acceptance

import androidx.work.ListenableWorker
import com.zero.v2.core.data.entity.ReminderEntity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** Real Worker/Room, denied permission injected only in the isolated module Context. */
class ReminderNotificationFailureAcceptanceTest {
    @Test fun deniedNotificationKeepsOnceReminderForExplicitRecovery() = runBlocking {
        WorkerReminderDeliveryAcceptanceTest().fixture(notificationsAllowed = false) { module, db, manager, run, count ->
            assertEquals(ListenableWorker.Result.failure(), run())
            assertTrue("Expected rejection must keep the module operational", manager.isOperational(module.metadata.id))
            assertEquals(ReminderEntity.STATUS_MISSED, db.systemStateDao().loadReminders().single().status)
            assertEquals(0, count())
            module.restoreAfterBoot()
            assertEquals(Long.MAX_VALUE, module.nextRunTime)
            assertEquals(ListenableWorker.Result.success(), run())
            assertEquals(1, db.systemStateDao().loadReminders().size)
            assertEquals(0, count())
        }
    }

    @Test fun deniedRecurringReminderRetainsItsRuleAndOccurrence() = runBlocking {
        WorkerReminderDeliveryAcceptanceTest().fixture(notificationsAllowed = false, recurring = true) { _, db, _, run, count ->
            val original = db.systemStateDao().loadReminders().single()
            assertEquals(ListenableWorker.Result.failure(), run())
            assertEquals(original.copy(status = ReminderEntity.STATUS_MISSED), db.systemStateDao().loadReminders().single())
            assertEquals(0, count())
        }
    }

    @Test fun systemSubmissionFailureRemainsRecoverable() = runBlocking {
        WorkerReminderDeliveryAcceptanceTest().fixture(notificationServiceFails = true) { _, db, _, run, count ->
            assertEquals(ListenableWorker.Result.failure(), run())
            assertEquals(ReminderEntity.STATUS_MISSED, db.systemStateDao().loadReminders().single().status)
            assertEquals(0, count())
        }
    }

    @Test fun failedMissedCommitNeverAcknowledgesOrDeletesTheOriginal() = runBlocking {
        WorkerReminderDeliveryAcceptanceTest().fixture(notificationsAllowed = false) { _, db, _, run, count ->
            val original = db.systemStateDao().loadReminders().single()
            db.openHelper.writableDatabase.execSQL("CREATE TRIGGER reject_missed BEFORE UPDATE OF status ON reminders " +
                "WHEN NEW.status = 'missed' BEGIN SELECT RAISE(ABORT, 'isolated write rejection'); END")
            assertEquals(ListenableWorker.Result.failure(), run())
            assertEquals(original, db.systemStateDao().loadReminders().single())
            assertEquals(0, count())
        }
    }

    @Test fun staleFailureCannotOverwriteRescheduledOrCancelledRow() = runBlocking {
        WorkerReminderDeliveryAcceptanceTest().fixture { _, db, _, _, _ ->
            val dao = db.systemStateDao()
            val original = dao.loadReminders().single()
            val newer = original.copy(triggerAt = original.triggerAt + 60_000)
            dao.saveReminder(newer)
            assertEquals(0, dao.markUndeliveredIfActive(original.id, original.triggerAt))
            assertEquals(newer, dao.loadReminders().single())
            dao.deleteReminder(original.id)
            assertEquals(0, dao.markUndeliveredIfActive(original.id, original.triggerAt))
            assertTrue(dao.loadReminders().isEmpty())
        }
    }
}
