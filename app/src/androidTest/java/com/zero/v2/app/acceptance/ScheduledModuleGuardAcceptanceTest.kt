package com.zero.v2.app.acceptance

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.zero.v2.core.bus.ChatBusImpl
import com.zero.v2.core.bus.SystemBusImpl
import com.zero.v2.core.common.*
import com.zero.v2.core.data.db.ZeroDatabase
import com.zero.v2.core.data.store.acceptanceConfigStore
import com.zero.v2.core.moduleapi.*
import com.zero.v2.core.modulemanager.ModuleLifecycleManager
import com.zero.v2.core.trace.AgentTraceManager
import com.zero.v2.services.agent.AgentJob
import com.zero.v2.services.agent.Scheduler
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/** Real scheduler and lifecycle guard, isolated Room and local-only scheduled module. */
class ScheduledModuleGuardAcceptanceTest {
    private class ScheduledModule(val action: suspend () -> Unit) : IZeroModule, AgentJob {
        override val metadata = ModuleMetadata("isolated_scheduled", "隔离定时模块", "本地测试", ModuleCategory.AUTOMATION, 10)
        override val id = "isolated_job"
        override val nextRunTime = 0L
        override val lifecycle = MutableStateFlow(ModuleLifecycle.UNINITIALIZED)
        override var isEnabled = true
        override val requiredPermissions = emptyList<String>()
        override val config = object : ModuleConfigBase() {
            override fun toJson() = "{}"
            override fun fromJson(json: String) = Unit
            override fun defaultConfig() = this
        }
        override suspend fun onAwake() { lifecycle.value = ModuleLifecycle.AWAKE }
        override suspend fun onStart() { lifecycle.value = ModuleLifecycle.STARTED }
        override suspend fun onDestroy() { lifecycle.value = ModuleLifecycle.DESTROYED }
        override suspend fun onConfigChanged(newConfig: ModuleConfigBase) = Unit
        override suspend fun checkAvailability() = true
        override fun markDisabled() { lifecycle.value = ModuleLifecycle.DISABLED }
        override suspend fun execute() = action()
    }

    private suspend fun fixture(
        action: suspend () -> Unit,
        block: suspend (ScheduledModule, ModuleLifecycleManager, suspend () -> Unit) -> Unit,
    ) {
        val db = Room.inMemoryDatabaseBuilder(InstrumentationRegistry.getInstrumentation().targetContext, ZeroDatabase::class.java).build()
        val worker = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val store = acceptanceConfigStore(db.moduleConfigDao(), worker)
        val bus = SystemBusImpl()
        val trace = AgentTraceManager(db.agentTraceEventDao())
        val module = ScheduledModule(action)
        val completedTicks = AtomicInteger()
        val barrier = object : AgentJob {
            override val id = "isolated_barrier"
            override val nextRunTime = 0L
            override suspend fun execute() { completedTicks.incrementAndGet() }
        }
        lateinit var manager: ModuleLifecycleManager
        val scheduler = Scheduler(bus, linkedSetOf(module, barrier), trace, javax.inject.Provider { manager })
        val catalog = object : FunctionCatalog {
            override fun getAllDefinitions() = emptyList<FunctionDefinition>()
            override fun getEntriesByModule(moduleId: String) = emptyList<FunctionEntry>()
        }
        val registrar = object : ToolRegistrar {
            override fun register(definition: FunctionDefinition, sourceModuleId: String, permission: ActionPermission, handler: suspend (Map<String, Any?>) -> FunctionResult) = Unit
            override fun unregister(name: String) = Unit
            override fun registerContributions(module: IZeroModule) = 0
        }
        manager = ModuleLifecycleManager(setOf(scheduler, module), ChatBusImpl(), store, trace, catalog, registrar)
        try {
            manager.initializeAll()
            block(module, manager) {
                val expected = completedTicks.get() + 1
                bus.onTick.emit(System.currentTimeMillis())
                withTimeout(3000) { while (completedTicks.get() < expected) delay(10) }
            }
        } finally { manager.destroyAll(); worker.cancel(); delay(100); db.close() }
    }

    @Test fun actualModuleCenterDisableBlocksTickAndReenableRestoresIt() = runBlocking {
        val executions = AtomicInteger()
        fixture({ executions.incrementAndGet(); Unit }) { module, manager, tick ->
            assertTrue(manager.setUserEnabled(module.metadata.id, false))
            tick()
            assertEquals("Module center disable must gate scheduled calls", 0, executions.get())
            manager.setUserEnabled(module.metadata.id, true)
            tick()
            assertEquals(1, executions.get())
        }
    }

    @Test fun destroyedModuleCannotExecuteEvenWhenItsDeadlineIsDue() = runBlocking {
        val executions = AtomicInteger()
        fixture({ executions.incrementAndGet(); Unit }) { module, _, tick ->
            module.onDestroy()
            tick()
            assertEquals("Destroyed lifecycle must gate scheduled calls", 0, executions.get())
        }
    }

    @Test fun scheduledCrashUsesActualSafeCallAndDisablesNonCoreOwner() = runBlocking {
        val executions = AtomicInteger()
        fixture({ executions.incrementAndGet(); error("isolated module crash") }) { module, manager, tick ->
            tick()
            assertFalse("Scheduled exceptions must reach lifecycle crash isolation", manager.isOperational(module.metadata.id))
            assertEquals(ModuleLifecycle.DISABLED, module.lifecycle.value)
            tick()
            assertEquals(1, executions.get())
        }
    }

    @Test fun localCancellationDoesNotDisableModuleAndNextTickCanRetry() = runBlocking {
        val executions = AtomicInteger()
        fixture({ if (executions.incrementAndGet() == 1) throw CancellationException("isolated cancellation") }) { module, manager, tick ->
            tick()
            assertTrue(manager.isOperational(module.metadata.id))
            tick()
            assertEquals(2, executions.get())
        }
    }
}
