package com.zero.v2.app.acceptance

import android.app.NotificationManager
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.*
import androidx.work.testing.TestListenableWorkerBuilder
import com.zero.v2.app.background.AlarmWorkExecution
import com.zero.v2.app.background.AutomationWorker
import com.zero.v2.core.bus.ChatBusImpl
import com.zero.v2.core.bus.SystemBusImpl
import com.zero.v2.core.common.*
import com.zero.v2.core.data.db.ZeroDatabase
import com.zero.v2.core.data.entity.ReminderEntity
import com.zero.v2.core.data.store.acceptanceConfigStore
import com.zero.v2.core.moduleapi.*
import com.zero.v2.core.modulemanager.ModuleLifecycleManager
import com.zero.v2.core.trace.AgentTraceManager
import com.zero.v2.services.agent.*
import com.zero.v2.services.module.SystemEventModule
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import javax.inject.Provider

/** Original Worker, real guard/module/notification, isolated Room; Runtime never starts. */
class WorkerReminderDeliveryAcceptanceTest {
    internal suspend fun fixture(notificationsAllowed: Boolean = true, recurring: Boolean = false,
        notificationServiceFails: Boolean = false,
        block: suspend (SystemEventModule, ZeroDatabase, ModuleLifecycleManager,
        suspend () -> ListenableWorker.Result, () -> Int) -> Unit) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertEquals(PackageManager.PERMISSION_GRANTED,
            context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS))
        val db = Room.inMemoryDatabaseBuilder(context, ZeroDatabase::class.java).build()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val store = acceptanceConfigStore(db.moduleConfigDao(), scope)
        val chat = ChatBusImpl()
        val bus = SystemBusImpl()
        val trace = AgentTraceManager(db.agentTraceEventDao())
        val runtime = AgentRuntime(bus, chat, trace, store, dagger.Lazy { error("No models") })
        val isolated = object : ContextWrapper(context) {
            override fun checkSelfPermission(permission: String): Int =
                if (!notificationsAllowed && permission == android.Manifest.permission.POST_NOTIFICATIONS)
                    PackageManager.PERMISSION_DENIED else super.checkSelfPermission(permission)
            override fun getSystemService(name: String): Any? {
                check(name != Context.ALARM_SERVICE) { "No personal alarms" }
                check(!notificationServiceFails || name != Context.NOTIFICATION_SERVICE) { "Isolated notification rejection" }
                return super.getSystemService(name)
            }
        }
        val module = EntryPointAccessors.fromApplication(context, RegenerationAcceptanceEntryPoint::class.java)
            .automationAcceptanceFactory().createSystem(isolated, db, store, runtime, chat)
        val catalog = object : FunctionCatalog {
            override fun getAllDefinitions() = emptyList<FunctionDefinition>()
            override fun getEntriesByModule(moduleId: String) = emptyList<FunctionEntry>()
        }
        val registrar = object : ToolRegistrar {
            override fun register(definition: FunctionDefinition, sourceModuleId: String,
                permission: ActionPermission, handler: suspend (Map<String, Any?>) -> FunctionResult) = Unit
            override fun unregister(name: String) = Unit
            override fun registerContributions(module: IZeroModule) = 0
        }
        val manager = ModuleLifecycleManager(setOf(module), chat, store, trace, catalog, registrar)
        val dispatcher = AlarmDueDispatcher(Provider { module }, Provider { error("No automation") },
            bus, trace, Provider { manager })
        val execution = AlarmWorkExecution(ModuleInitializationGate().apply { complete() }, Provider { dispatcher }, trace)
        val factory = object : WorkerFactory() {
            override fun createWorker(appContext: Context, workerClassName: String, params: WorkerParameters): ListenableWorker? =
                if (workerClassName == AutomationWorker::class.java.name) AutomationWorker(appContext, params, execution) else null
        }
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val content = "A8 isolated Worker reminder ${java.util.UUID.randomUUID()}"
        fun ownNotifications() = nm.activeNotifications.filter {
            it.notification.extras.getCharSequence(android.app.Notification.EXTRA_TEXT)?.toString() == content
        }
        try {
            val row = ReminderEntity("isolated-worker-reminder", content, System.currentTimeMillis() + 700,
                isRecurring = recurring, recurringMs = if (recurring) 60_000 else 0)
            db.systemStateDao().saveReminder(row)
            module.restoreAfterBoot()
            delay((row.triggerAt - System.currentTimeMillis() + 20).coerceAtLeast(0))
            val runWorker: suspend () -> ListenableWorker.Result = {
                TestListenableWorkerBuilder<AutomationWorker>(context,
                    inputData = Data.Builder().putString(AutomationWorker.KEY_ACTION, ExactAlarmScheduler.ACTION_REMINDER_DUE).build())
                    .setWorkerFactory(factory).build().doWork()
            }
            block(module, db, manager, runWorker) { ownNotifications().size }
        } finally {
            ownNotifications().forEach { nm.cancel(it.tag, it.id) }
            module.onDestroy(); runtime.stopLoop(); scope.cancel(); delay(100); db.close()
        }
    }

    @Test fun originalWorkerAlonePostsOneNotificationAndCommitsDeletion() = runBlocking {
        fixture { _, db, _, runWorker, count ->
            assertEquals(0, count())
            assertEquals(ListenableWorker.Result.success(), runWorker())
            withTimeout(3000) { while (count() == 0) delay(20) }
            assertEquals(1, count())
            assertTrue(db.systemStateDao().loadReminders().isEmpty())
            assertEquals(ListenableWorker.Result.success(), runWorker())
            assertEquals(1, count())
        }
    }

    @Test fun cancelledReminderCannotReappearAfterRestoreOrWorkerScan() = runBlocking {
        fixture { module, db, _, runWorker, count ->
            assertTrue(module.contributions().single { it.definition.name == "cancel_reminder" }
                .handler(mapOf("reminder_id" to "isolated-worker-reminder")).success)
            module.restoreAfterBoot()
            assertEquals(ListenableWorker.Result.success(), runWorker())
            assertTrue(db.systemStateDao().loadReminders().isEmpty())
            assertEquals(Long.MAX_VALUE, module.nextRunTime)
            assertEquals(0, count())
        }
    }

    @Test fun disabledModuleCannotNotifyOrDeleteItsPendingReminder() = runBlocking {
        fixture { module, db, manager, runWorker, count ->
            manager.setUserEnabled(module.metadata.id, false)
            assertEquals(ListenableWorker.Result.failure(), runWorker())
            assertEquals(1, db.systemStateDao().loadReminders().size)
            assertEquals(0, count())
        }
    }
}
