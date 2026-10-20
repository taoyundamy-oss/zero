package com.zero.v2.app.acceptance

import android.content.Context
import android.content.ContextWrapper
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.zero.v2.core.bus.ChatBusImpl
import com.zero.v2.core.bus.SystemBusImpl
import com.zero.v2.core.data.db.ZeroDatabase
import com.zero.v2.core.data.entity.ReminderEntity
import com.zero.v2.core.data.store.acceptanceConfigStore
import com.zero.v2.core.trace.AgentTraceManager
import com.zero.v2.services.agent.AgentRuntime
import com.zero.v2.core.moduleapi.*
import com.zero.v2.core.common.*
import com.zero.v2.core.modulemanager.ModuleLifecycleManager
import com.zero.v2.services.agent.AlarmDueDispatcher
import com.zero.v2.services.agent.ModuleInitializationGate
import com.zero.v2.app.background.AlarmWorkExecution
import androidx.work.ListenableWorker
import javax.inject.Provider
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

/** Real reminder recovery with private Room and rejecting alarm context. No notifications or models. */
class BootRecoveryAcceptanceTest {
    private suspend fun fixture(permissionRead: Boolean = false, block: suspend (com.zero.v2.services.module.SystemEventModule, ZeroDatabase,
        AlarmDueDispatcher, ModuleLifecycleManager) -> Unit) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context, ZeroDatabase::class.java).build()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val store = acceptanceConfigStore(db.moduleConfigDao(), scope)
        val chat = ChatBusImpl()
        val runtime = AgentRuntime(SystemBusImpl(), chat, AgentTraceManager(db.agentTraceEventDao()), store,
            dagger.Lazy { error("No model requests permitted") })
        val permissionReadAvailable = java.util.concurrent.atomic.AtomicBoolean(permissionRead)
        val isolated = object : ContextWrapper(context) {
            override fun getSystemService(name: String): Any? {
                if (name == Context.ALARM_SERVICE) check(permissionReadAvailable.compareAndSet(true, false)) {
                    "Only the empty-module startup permission read is allowed; no alarm scheduling"
                }
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
            override fun register(definition: FunctionDefinition, sourceModuleId: String, permission: ActionPermission,
                handler: suspend (Map<String, Any?>) -> FunctionResult) = Unit
            override fun unregister(name: String) = Unit
            override fun registerContributions(module: IZeroModule) = 0
        }
        val trace = AgentTraceManager(db.agentTraceEventDao())
        val manager = ModuleLifecycleManager(setOf(module), chat, store, trace, catalog, registrar)
        val dispatcher = AlarmDueDispatcher(Provider { module }, Provider { error("No automation permitted") },
            SystemBusImpl(), trace, Provider { manager })
        try {
            block(module, db, dispatcher, manager)
        } finally {
            module.onDestroy(); runtime.stopLoop(); scope.cancel(); delay(100); db.close()
        }
    }

    @Test fun disabledReminderSourceMustNotChangePersistedRowsOnBootRecovery() = runBlocking {
        fixture { module, db, _, _ ->
            db.systemStateDao().saveReminder(ReminderEntity(id = "isolated-expired", triggerAt = 1, remark = "isolated"))
            module.isEnabled = false
            module.rescheduleAllOnBoot()
            delay(300)
            assertEquals(ReminderEntity.STATUS_ACTIVE, db.systemStateDao().loadReminders().single().status)
        }
    }

    @Test fun recoveryAwaitsCommitAndPreservesSkipAndRecurringPhase() = runBlocking {
        fixture { module, db, _, _ ->
            val now = System.currentTimeMillis()
            val phase = now - 1000
            db.systemStateDao().saveReminder(ReminderEntity("once", "isolated", phase))
            db.systemStateDao().saveReminder(ReminderEntity("repeat", "isolated", phase, repeatRule = "interval:3600000"))
            module.restoreAfterBoot()
            val first = db.systemStateDao().loadReminders().associateBy { it.id }
            assertEquals(first, module.remindersState.value.associateBy { it.id })
            assertEquals(ReminderEntity.STATUS_MISSED, first.getValue("once").status)
            assertEquals(phase + 3600000, first.getValue("repeat").triggerAt)
            module.restoreAfterBoot()
            assertEquals(first, db.systemStateDao().loadReminders().associateBy { it.id })
        }
    }

    @Test fun cancelledRecoveryDoesNotMutateRowsOrReportCompletion() = runBlocking {
        fixture { module, db, _, _ ->
            db.systemStateDao().saveReminder(ReminderEntity("cancel", "isolated", 1))
            val job = launch(start = CoroutineStart.UNDISPATCHED) {
                currentCoroutineContext().cancel()
                module.restoreAfterBoot()
                fail("Cancellation must propagate")
            }
            job.join()
            assertTrue(job.isCancelled)
            assertEquals(ReminderEntity.STATUS_ACTIVE, db.systemStateDao().loadReminders().single().status)
        }
    }

    @Test fun bootWorkerWaitsForInitializationAndUsesActualModuleGuard() = runBlocking {
        fixture(permissionRead = true) { module, db, dispatcher, manager ->
            manager.initializeAll()
            db.systemStateDao().saveReminder(ReminderEntity("worker", "isolated", 1))
            val gate = ModuleInitializationGate()
            val execution = AlarmWorkExecution(gate, Provider { dispatcher }, AgentTraceManager(db.agentTraceEventDao()))
            val pending = async(start = CoroutineStart.UNDISPATCHED) { execution.run(AlarmDueDispatcher.ACTION_BOOT_RESTORE, 0) }
            assertFalse(pending.isCompleted)
            assertEquals(ReminderEntity.STATUS_ACTIVE, db.systemStateDao().loadReminders().single().status)
            gate.complete()
            assertEquals(ListenableWorker.Result.success(), pending.await())
            assertEquals(ReminderEntity.STATUS_MISSED, db.systemStateDao().loadReminders().single().status)
            manager.setUserEnabled(module.metadata.id, false)
            assertEquals(ListenableWorker.Result.failure(), execution.run(AlarmDueDispatcher.ACTION_BOOT_RESTORE, 0))
        }
    }
}
