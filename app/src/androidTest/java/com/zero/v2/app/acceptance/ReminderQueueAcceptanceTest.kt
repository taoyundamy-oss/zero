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
import com.zero.v2.services.module.SystemEventModule
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class ReminderQueueAcceptanceTest {
    private suspend fun fixture(onDelivery: (ZeroDatabase, ReminderEntity) -> Unit,
        block: suspend (SystemEventModule, ZeroDatabase, ReminderEntity) -> Unit) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context, ZeroDatabase::class.java).build()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val store = acceptanceConfigStore(db.moduleConfigDao(), scope)
        val chat = ChatBusImpl()
        val runtime = AgentRuntime(SystemBusImpl(), chat, AgentTraceManager(db.agentTraceEventDao()), store,
            dagger.Lazy { error("No models") })
        val row = ReminderEntity("isolated-queue", "isolated", System.currentTimeMillis() + 500)
        val isolated = object : ContextWrapper(context) {
            override fun getSystemService(name: String): Any? {
                check(name != Context.ALARM_SERVICE) { "No personal alarms" }
                return super.getSystemService(name)
            }
            override fun checkSelfPermission(permission: String): Int {
                check(permission == android.Manifest.permission.POST_NOTIFICATIONS)
                onDelivery(db, row)
                return PackageManager.PERMISSION_GRANTED
            }
        }
        val module = EntryPointAccessors.fromApplication(context, RegenerationAcceptanceEntryPoint::class.java)
            .automationAcceptanceFactory().createSystem(isolated, db, store, runtime, chat)
        try {
            db.systemStateDao().saveReminder(row)
            module.restoreAfterBoot()
            delay((row.triggerAt - System.currentTimeMillis() + 20).coerceAtLeast(0))
            block(module, db, row)
        } finally {
            val notifications = context.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
            notifications.activeNotifications.filter { it.tag == "zero_reminder:${row.id}:${row.triggerAt}" }
                .forEach { notifications.cancel(it.tag, it.id) }
            module.onDestroy(); runtime.stopLoop(); scope.cancel(); delay(100); db.close()
        }
    }

    @Test fun oldOneShotDeliveryCannotDeleteRescheduledReminder() = runBlocking {
        fixture({ db, row -> runBlocking {
            db.systemStateDao().updateSchedule(row.id, row.triggerAt + 60000, null, false, 0,
                ReminderEntity.STATUS_ACTIVE, "rescheduled")
        } }) { module, db, row ->
            module.execute()
            delay(200)
            assertEquals(row.copy(triggerAt = row.triggerAt + 60000, remark = "rescheduled"), db.systemStateDao().loadReminders().singleOrNull())
        }
    }

    private suspend fun blockedDelivery(recover: Boolean) {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        fixture({ _, _ -> entered.countDown(); check(release.await(5, TimeUnit.SECONDS)) }) { module, _, row ->
            coroutineScope {
                val first = async(Dispatchers.Default) { module.execute() }
                assertTrue(entered.await(3, TimeUnit.SECONDS))
                val other = async(Dispatchers.Default) {
                    if (recover) module.restoreAfterBoot()
                    else module.contributions().single { it.definition.name == "cancel_reminder" }.handler(mapOf("reminder_id" to row.id))
                }
                try {
                    delay(100)
                    assertFalse("Mutation must wait for the claimed delivery", other.isCompleted)
                } finally { release.countDown(); first.await(); other.await() }
            }
        }
    }

    @Test fun recoveryWaitsForClaimedDelivery() = runBlocking { blockedDelivery(true) }
    @Test fun cancellationWaitsForClaimedDelivery() = runBlocking { blockedDelivery(false) }

    @Test fun createAndCancelAcknowledgementsMatchCommittedRows() = runBlocking {
        fixture({ _, _ -> error("No delivery expected") }) { module, db, original ->
            val create = module.contributions().single { it.definition.name == "set_reminder" }
            assertTrue(create.handler(mapOf("time" to "+1h", "remark" to "created locally")).success)
            val created = db.systemStateDao().loadReminders().single { it.id != original.id }
            assertEquals("created locally", created.remark)
            assertTrue(module.contributions().single { it.definition.name == "cancel_reminder" }
                .handler(mapOf("reminder_id" to created.id)).success)
            assertEquals(listOf(original), db.systemStateDao().loadReminders())
        }
    }

    @Test fun offModeReminderDeliveryCannotQueueResidualAutomaticTick() = runBlocking {
        fixture({ _, _ -> }) { module, db, _ ->
            db.moduleConfigDao().upsert(com.zero.v2.core.data.entity.ModuleConfigEntity(
                "agent_behavior", "{\"mode\":\"OFF\"}"))
            // Reproduce an automatic deadline that expired while the process was absent.
            SystemEventModule::class.java.getDeclaredField("nextAutoTick").apply { isAccessible = true }.setLong(module, 0)
            module.execute()
            assertTrue(db.systemStateDao().loadReminders().isEmpty())
            assertEquals("OFF must not publish an automatic wake after reminder delivery", Long.MAX_VALUE, module.nextRunTime)
        }
    }
}
