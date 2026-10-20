package com.zero.v2.app.acceptance

import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.zero.v2.core.bus.ChatBusImpl
import com.zero.v2.core.bus.SystemBusImpl
import com.zero.v2.core.data.db.ZeroDatabase
import com.zero.v2.core.data.entity.ReminderEntity
import com.zero.v2.core.data.store.acceptanceConfigStore
import com.zero.v2.core.trace.AgentTraceManager
import com.zero.v2.services.agent.AgentRuntime
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

/** Real due path/notification, private Room, and rejecting alarm/model context. */
class RecurringReminderWriteAcceptanceTest {
    private suspend fun fixture(deleteAtDelivery: Boolean, block: suspend (ZeroDatabase, ReminderEntity) -> Unit) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context, ZeroDatabase::class.java).build()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val store = acceptanceConfigStore(db.moduleConfigDao(), scope)
        val chat = ChatBusImpl()
        val runtime = AgentRuntime(SystemBusImpl(), chat, AgentTraceManager(db.agentTraceEventDao()), store,
            dagger.Lazy { error("No model requests") })
        val isolated = object : ContextWrapper(context) {
            override fun getSystemService(name: String): Any? {
                check(name != Context.ALARM_SERVICE) { "No personal alarms" }
                return super.getSystemService(name)
            }
            override fun checkSelfPermission(permission: String): Int {
                check(permission == android.Manifest.permission.POST_NOTIFICATIONS)
                if (deleteAtDelivery) runBlocking { db.systemStateDao().deleteReminder("isolated-recurring-write") }
                return PackageManager.PERMISSION_GRANTED
            }
        }
        val module = EntryPointAccessors.fromApplication(context, RegenerationAcceptanceEntryPoint::class.java)
            .automationAcceptanceFactory().createSystem(isolated, db, store, runtime, chat)
        val original = ReminderEntity("isolated-recurring-write", "isolated", System.currentTimeMillis() + 1000,
            true, 3600000, title = "keep title", note = "keep note", createdBy = ReminderEntity.CREATED_BY_LLM,
            repeatRule = "interval:3600000")
        try {
            db.systemStateDao().saveReminder(original)
            module.restoreAfterBoot()
            delay((original.triggerAt - System.currentTimeMillis() + 20).coerceAtLeast(0))
            module.execute()
            // Old code commits asynchronously: let the real write complete before checking.
            delay(200)
            block(db, original)
        } finally {
            val notifications = context.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
            notifications.activeNotifications.filter { it.tag == "zero_reminder:${original.id}:${original.triggerAt}" }
                .forEach { notifications.cancel(it.tag, it.id) }
            module.onDestroy(); runtime.stopLoop(); scope.cancel(); delay(100); db.close()
        }
    }

    @Test fun recurringDeliveryPreservesAllNonScheduleFields() = runBlocking {
        fixture(false) { db, original ->
            val saved = db.systemStateDao().loadReminders().single()
            assertEquals(original.copy(triggerAt = original.triggerAt + 3600000), saved)
        }
    }

    @Test fun deletionDuringDeliveryCannotBeResurrectedByRecurringWrite() = runBlocking {
        fixture(true) { db, _ -> assertTrue("Deleted reminder must stay deleted", db.systemStateDao().loadReminders().isEmpty()) }
    }

    @Test fun staleAdvanceCannotOverrideRescheduleOrDisabledRow() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context, ZeroDatabase::class.java).build()
        try {
            val dao = db.systemStateDao()
            val original = ReminderEntity("isolated-cas", "edited", 5000, true, 3600000,
                title = "edited title", note = "edited note", repeatRule = "interval:3600000")
            dao.saveReminder(original)
            assertEquals(0, dao.advanceRecurringIfActive(original.id, 1000, 3601000))
            assertEquals(original, dao.loadReminders().single())
            dao.updateStatus(original.id, ReminderEntity.STATUS_DONE)
            assertEquals(0, dao.advanceRecurringIfActive(original.id, 5000, 3605000))
            assertEquals(original.copy(status = ReminderEntity.STATUS_DONE), dao.loadReminders().single())
        } finally { db.close() }
    }
}
