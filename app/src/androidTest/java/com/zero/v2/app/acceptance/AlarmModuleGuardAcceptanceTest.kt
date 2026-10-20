package com.zero.v2.app.acceptance

import android.content.Context
import android.content.ContextWrapper
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.zero.v2.core.bus.ChatBusImpl
import com.zero.v2.core.bus.SystemBusImpl
import com.zero.v2.core.common.*
import com.zero.v2.core.data.db.ZeroDatabase
import com.zero.v2.core.data.entity.AutomationEntity
import com.zero.v2.core.data.store.acceptanceConfigStore
import com.zero.v2.core.moduleapi.*
import com.zero.v2.core.trace.AgentTraceManager
import com.zero.v2.services.agent.AgentRuntime
import com.zero.v2.services.agent.AlarmDueDispatcher
import com.zero.v2.services.agent.ExactAlarmScheduler
import com.zero.v2.core.modulemanager.ModuleLifecycleManager
import com.zero.v2.services.module.AutomationModule
import com.zero.v2.services.module.ExecutionPlan
import com.zero.v2.services.module.PlanStep
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/** Actual dispatch/Room path; no personal tasks, alarms or provider calls. */
class AlarmModuleGuardAcceptanceTest {
    internal suspend fun fixture(
        tool: suspend () -> FunctionResult,
        persistentName: String? = null,
        seed: Boolean = true,
        block: suspend (AutomationModule, ZeroDatabase, AlarmDueDispatcher, ModuleLifecycleManager) -> Unit,
    ) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = if (persistentName == null) Room.inMemoryDatabaseBuilder(context, ZeroDatabase::class.java).build()
        else {
            require(persistentName == "a8_disk_recovery_fixture.db")
            Room.databaseBuilder(context, ZeroDatabase::class.java, persistentName).build()
        }
        val worker = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val store = acceptanceConfigStore(db.moduleConfigDao(), worker)
        val chat = ChatBusImpl()
        val trace = AgentTraceManager(db.agentTraceEventDao())
        val runtime = AgentRuntime(SystemBusImpl(), chat, trace, store, dagger.Lazy { error("No provider permitted") })
        val preferenceName = "a8_isolated_alarm_guard_budget"
        context.deleteSharedPreferences(preferenceName)
        val isolated = object : ContextWrapper(context) {
            override fun getSharedPreferences(name: String, mode: Int) = super.getSharedPreferences(preferenceName, mode)
            override fun getSystemService(name: String): Any? {
                check(name != Context.ALARM_SERVICE) { "No personal alarms permitted" }
                return super.getSystemService(name)
            }
        }
        val tools = object : ToolRuntime {
            override fun getAllDefinitions() = emptyList<FunctionDefinition>()
            override fun getEntriesByModule(moduleId: String) = emptyList<FunctionEntry>()
            override suspend fun execute(name: String, arguments: Map<String, Any?>): FunctionResult {
                check(name == "isolated_echo")
                return tool()
            }
            override suspend fun execute(toolCall: ToolCall): FunctionResult = error("No model tool calls")
            override suspend fun executeBatch(toolCalls: List<ToolCall>): List<FunctionResult> = error("No batches")
        }
        val module = EntryPointAccessors.fromApplication(context, RegenerationAcceptanceEntryPoint::class.java)
            .automationAcceptanceFactory().create(isolated, db, store, runtime, chat, tools)
        val catalog = object : FunctionCatalog {
            override fun getAllDefinitions() = emptyList<FunctionDefinition>()
            override fun getEntriesByModule(moduleId: String) = emptyList<FunctionEntry>()
        }
        val registrar = object : ToolRegistrar {
            override fun register(definition: FunctionDefinition, sourceModuleId: String, permission: ActionPermission, handler: suspend (Map<String, Any?>) -> FunctionResult) = Unit
            override fun unregister(name: String) = Unit
            override fun registerContributions(module: IZeroModule) = 0
        }
        val manager = ModuleLifecycleManager(setOf(module), chat, store, trace, catalog, registrar)
        val dispatcher = AlarmDueDispatcher(javax.inject.Provider { error("Reminder provider must not be resolved for automation") },
            javax.inject.Provider { module }, SystemBusImpl(), trace, javax.inject.Provider { manager })
        try {
            if (seed) db.automationDao().insert(AutomationEntity("isolated-dispatch", "隔离调度", "本地测试",
                ExecutionPlan("isolated goal", listOf(PlanStep(1, "local echo", "isolated_echo"))).toJson(),
                scheduleType = "INTERVAL", scheduleValue = "3600", nextRunAt = 0))
            manager.initializeAll()
            block(module, db, dispatcher, manager)
        } finally {
            manager.destroyAll(); runtime.stopLoop(); worker.cancel(); delay(100)
            db.close(); context.deleteSharedPreferences(preferenceName)
        }
    }

    @Test fun alarmHonorsActualModuleCenterDisable() = runBlocking {
        fixture({ FunctionResult.success("local") }) { module, db, dispatcher, manager ->
            manager.setUserEnabled(module.metadata.id, false)
            dispatcher.dispatch(ExactAlarmScheduler.ACTION_AUTOMATION_DUE)
            assertEquals("Alarm must honor module center disable", 0, requireNotNull(db.automationDao().getById("isolated-dispatch")).runCount)
        }
    }

    @Test fun alarmCannotRunDestroyedModule() = runBlocking {
        fixture({ FunctionResult.success("local") }) { module, db, dispatcher, _ ->
            module.onDestroy()
            dispatcher.dispatch(ExactAlarmScheduler.ACTION_AUTOMATION_DUE)
            assertEquals("Alarm must honor destroyed lifecycle", 0, requireNotNull(db.automationDao().getById("isolated-dispatch")).runCount)
        }
    }

    @Test fun enabledAlarmRunsActualPlanAndReschedulesOnce() = runBlocking {
        val executions = AtomicInteger()
        fixture({ executions.incrementAndGet(); FunctionResult.success("local") }) { module, db, dispatcher, _ ->
            dispatcher.dispatch(ExactAlarmScheduler.ACTION_AUTOMATION_DUE)
            dispatcher.dispatch(ExactAlarmScheduler.ACTION_AUTOMATION_DUE)
            assertEquals(1, executions.get())
            val saved = requireNotNull(db.automationDao().getById("isolated-dispatch"))
            assertEquals(1, saved.runCount)
            assertTrue(saved.nextRunAt > System.currentTimeMillis())
            assertTrue(module.runProgress.value?.done == true)
        }
    }

    @Test fun alarmDispatchPropagatesToolCancellationWithoutAdvancingTask() = runBlocking {
        fixture({ throw CancellationException("isolated alarm cancellation") }) { module, db, dispatcher, manager ->
            var cancelled = false
            try { dispatcher.dispatch(ExactAlarmScheduler.ACTION_AUTOMATION_DUE) } catch (_: CancellationException) { cancelled = true }
            assertTrue(cancelled)
            assertEquals(0, requireNotNull(db.automationDao().getById("isolated-dispatch")).runCount)
            assertFalse(module.runProgress.value?.done == true)
            assertTrue(manager.isOperational(module.metadata.id))
        }
    }
}
