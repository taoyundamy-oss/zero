package com.zero.v2.app.acceptance

import android.content.Context
import android.content.ContextWrapper
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.ListenableWorker
import com.zero.v2.core.bus.ChatBusImpl
import com.zero.v2.core.bus.SystemBusImpl
import com.zero.v2.core.data.db.ZeroDatabase
import com.zero.v2.core.data.entity.ReminderEntity
import com.zero.v2.core.data.store.acceptanceConfigStore
import com.zero.v2.core.trace.AgentTraceManager
import com.zero.v2.services.agent.AgentRuntime
import com.zero.v2.services.module.SystemEventModule
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

/** Real notification and SQLite rejection; fresh module owner, not a physical process restart. */
class ReminderCommitRecoveryAcceptanceTest {
    private suspend fun freshOwner(db: ZeroDatabase, block: suspend (SystemEventModule) -> Unit) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val store = acceptanceConfigStore(db.moduleConfigDao(), scope)
        val chat = ChatBusImpl()
        val runtime = AgentRuntime(SystemBusImpl(), chat, AgentTraceManager(db.agentTraceEventDao()), store,
            dagger.Lazy { error("No models") })
        val isolated = object : ContextWrapper(context) {
            override fun getSystemService(name: String): Any? {
                check(name != Context.ALARM_SERVICE) { "No personal alarms" }
                return super.getSystemService(name)
            }
        }
        val module = EntryPointAccessors.fromApplication(context, RegenerationAcceptanceEntryPoint::class.java)
            .automationAcceptanceFactory().createSystem(isolated, db, store, runtime, chat)
        try { module.restoreAfterBoot(); block(module) }
        finally { module.onDestroy(); runtime.stopLoop(); scope.cancel() }
    }

    private suspend fun rejectedCommit(recurring: Boolean) {
        WorkerReminderDeliveryAcceptanceTest().fixture(recurring = recurring) { _, db, _, run, count ->
            val original = db.systemStateDao().loadReminders().single()
            val operation = if (recurring) "UPDATE OF trigger_at" else "DELETE"
            db.openHelper.writableDatabase.execSQL("CREATE TRIGGER reject_commit BEFORE $operation ON reminders " +
                "BEGIN SELECT RAISE(ABORT, 'isolated commit rejection'); END")
            assertEquals(ListenableWorker.Result.failure(), run())
            assertEquals(original, db.systemStateDao().loadReminders().single())
            withTimeout(3000) { while (count() == 0) delay(20) }
            assertEquals(1, count())
            db.openHelper.writableDatabase.execSQL("DROP TRIGGER reject_commit")
            freshOwner(db) { module ->
                val restored = db.systemStateDao().loadReminders().single()
                if (recurring) {
                    assertTrue(restored.triggerAt > System.currentTimeMillis())
                    assertEquals(0L, (restored.triggerAt - original.triggerAt) % original.recurringMs)
                    assertEquals(original.copy(triggerAt = restored.triggerAt), restored)
                } else {
                    assertEquals(original.copy(status = ReminderEntity.STATUS_MISSED), restored)
                    assertEquals(Long.MAX_VALUE, module.nextRunTime)
                }
                module.execute()
                assertEquals(1, count())
            }
        }
    }

    @Test fun onceNotificationSurvivesFailedDeletionWithoutAutomaticReplay() = runBlocking {
        rejectedCommit(false)
    }

    @Test fun recurringNotificationSurvivesFailedAdvanceWithPhasePreserved() = runBlocking {
        rejectedCommit(true)
    }

    @Test fun deniedRecurringOccurrenceRequiresExplicitRequeueAfterRecovery() = runBlocking {
        WorkerReminderDeliveryAcceptanceTest().fixture(notificationsAllowed = false, recurring = true) { _, db, _, run, count ->
            val original = db.systemStateDao().loadReminders().single()
            assertEquals(ListenableWorker.Result.failure(), run())
            freshOwner(db) { module ->
                assertEquals(original.copy(status = ReminderEntity.STATUS_MISSED), db.systemStateDao().loadReminders().single())
                assertEquals(Long.MAX_VALUE, module.nextRunTime)
                module.execute()
                assertEquals(0, count())
            }
        }
    }
}
