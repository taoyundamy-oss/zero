package com.zero.v2.app.acceptance

import androidx.room.Room
import android.content.Context
import android.content.ContextWrapper
import androidx.test.platform.app.InstrumentationRegistry
import com.zero.v2.app.background.initializeAutonomousRuntime
import com.zero.v2.core.bus.ChatBusImpl
import com.zero.v2.core.bus.SystemBusImpl
import com.zero.v2.core.common.*
import com.zero.v2.core.data.db.ZeroDatabase
import com.zero.v2.core.data.entity.ModuleConfigEntity
import com.zero.v2.core.data.entity.AutomationEntity
import com.zero.v2.core.data.store.acceptanceConfigStore
import com.zero.v2.core.moduleapi.*
import com.zero.v2.core.modulemanager.ModuleLifecycleManager
import com.zero.v2.core.trace.AgentTraceManager
import com.zero.v2.services.agent.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import dagger.hilt.android.EntryPointAccessors
import com.zero.v2.services.module.ExecutionPlan
import com.zero.v2.services.module.PlanStep
import java.util.concurrent.atomic.AtomicInteger

/** Real application startup/lifecycle, runtime and scheduler, no accessibility or personal configuration. */
class AutonomousStartupAcceptanceTest {
    private suspend fun fixture(mode: String, realAutomation: Boolean = false, block: suspend (AgentRuntime, ChatBusImpl, AtomicInteger, AtomicInteger) -> Unit) {
        val db = Room.inMemoryDatabaseBuilder(InstrumentationRegistry.getInstrumentation().targetContext, ZeroDatabase::class.java).build()
        val worker = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val store = acceptanceConfigStore(db.moduleConfigDao(), worker)
        val chat = ChatBusImpl(); val system = SystemBusImpl(); val trace = AgentTraceManager(db.agentTraceEventDao())
        val attempts = AtomicInteger(); val executions = AtomicInteger()
        val runtime = AgentRuntime(system, chat, trace, store, dagger.Lazy { attempts.incrementAndGet(); error("Intercepted before paid provider or tools") })
        val due = object : AgentJob {
            override val id = "isolated_due_automation"
            override val nextRunTime: Long get() = if (executions.get() == 0) 0 else Long.MAX_VALUE
            override suspend fun execute() { executions.incrementAndGet() }
        }
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val preferenceName = "a8_isolated_startup_budget"
        context.deleteSharedPreferences(preferenceName)
        val isolated = object : ContextWrapper(context) {
            override fun getSharedPreferences(name: String, mode: Int) = super.getSharedPreferences(preferenceName, mode)
            override fun getSystemService(name: String): Any? {
                check(name != Context.ALARM_SERVICE) { "No personal alarms may be replaced" }
                return super.getSystemService(name)
            }
        }
        val tools = object : ToolRuntime {
            override fun getAllDefinitions() = emptyList<FunctionDefinition>()
            override fun getEntriesByModule(moduleId: String) = emptyList<FunctionEntry>()
            override suspend fun execute(name: String, arguments: Map<String, Any?>): FunctionResult {
                check(name == "isolated_echo"); executions.incrementAndGet(); return FunctionResult.success("isolated result")
            }
            override suspend fun execute(toolCall: ToolCall) = error("Only local typed tool permitted")
            override suspend fun executeBatch(toolCalls: List<ToolCall>) = error("No batch permitted")
        }
        val automation = if (realAutomation) EntryPointAccessors.fromApplication(context, RegenerationAcceptanceEntryPoint::class.java)
            .automationAcceptanceFactory().create(isolated, db, store, runtime, chat, tools) else null
        lateinit var lifecycle: ModuleLifecycleManager
        val scheduler = Scheduler(system, setOf(automation ?: due), trace, javax.inject.Provider { lifecycle })
        val catalog = object : FunctionCatalog {
            override fun getAllDefinitions() = emptyList<FunctionDefinition>()
            override fun getEntriesByModule(moduleId: String) = emptyList<FunctionEntry>()
        }
        val registrar = object : ToolRegistrar {
            override fun register(definition: FunctionDefinition, sourceModuleId: String, permission: ActionPermission, handler: suspend (Map<String, Any?>) -> FunctionResult) = Unit
            override fun unregister(name: String) = Unit
            override fun registerContributions(module: IZeroModule) = 0
        }
        val modules = mutableSetOf<IZeroModule>(runtime, scheduler).also { if (automation != null) it.add(automation) }
        lifecycle = ModuleLifecycleManager(modules, chat, store, trace, catalog, registrar)
        try {
            if (automation != null) db.automationDao().insert(AutomationEntity("isolated-startup", "隔离自动化", "本地测试",
                ExecutionPlan("isolated goal", listOf(PlanStep(1, "local echo", "isolated_echo"))).toJson(),
                scheduleType = "INTERVAL", scheduleValue = "3600", nextRunAt = 0))
            db.moduleConfigDao().upsert(ModuleConfigEntity("agent_behavior", "{\"mode\":\"$mode\",\"selfDriveOnTick\":true}"))
            initializeAutonomousRuntime(lifecycle, runtime)
            block(runtime, chat, attempts, executions)
            if (automation != null) {
                val saved = requireNotNull(db.automationDao().getById("isolated-startup"))
                assertEquals(if (mode == "OFF") 0 else 1, saved.runCount)
                if (mode != "OFF") {
                    assertNotNull(saved.lastRunAt); assertTrue(saved.nextRunAt > System.currentTimeMillis())
                    assertTrue(automation.runProgress.value?.done == true)
                }
            }
        } finally { lifecycle.destroyAll(); worker.cancel(); delay(100); db.close(); context.deleteSharedPreferences(preferenceName) }
    }

    @Test fun smartStartupWithoutAccessibilityStartsBothSelfDriveAndDueJobs() = runBlocking {
        fixture("SMART") { _, chat, attempts, executions ->
            delay(700)
            assertEquals("An enabled runtime must subscribe without accessibility", 1, chat.onChatSent.subscriptionCount.value)
            assertEquals("Startup heartbeat must reach an already subscribed scheduler", 1, executions.get())
            assertEquals("Self-drive entry must run without granting screen access", 1, attempts.get())
        }
    }
    @Test fun alwaysOnStartupWithoutAccessibilityStartsBothPaths() = runBlocking {
        fixture("ALWAYS_ON") { _, chat, attempts, executions ->
            delay(700); assertEquals(1, chat.onChatSent.subscriptionCount.value)
            assertEquals(1, executions.get()); assertEquals(1, attempts.get())
        }
    }
    @Test fun persistedOffDoesNotEmitHeartbeatOrSelfDrive() = runBlocking {
        fixture("OFF") { _, chat, attempts, executions ->
            delay(700); assertEquals(0, chat.onChatSent.subscriptionCount.value)
            assertEquals(0, executions.get()); assertEquals(0, attempts.get())
        }
    }
    @Test fun repeatedStartAndKicksDoNotDuplicateSubscriptionsOrSelfDriveRequests() = runBlocking {
        fixture("SMART") { runtime, chat, attempts, executions ->
            delay(700); runtime.startLoop(); runtime.startLoop(); runtime.kick()
            delay(500); assertEquals(1, chat.onChatSent.subscriptionCount.value)
            assertEquals(1, executions.get()); assertEquals(1, attempts.get())
        }
    }
    @Test fun actualAutomationModuleExecutesAndReschedulesOnStartupWithoutAccessibility() = runBlocking {
        fixture("SMART", realAutomation = true) { _, chat, attempts, executions ->
            withTimeout(3000) { while (executions.get() == 0) delay(10) }
            delay(300); assertEquals(1, executions.get()); assertEquals(1, attempts.get())
            assertEquals(1, chat.onChatSent.subscriptionCount.value)
        }
    }
    @Test fun actualAutomationModuleRemainsPausedWhenPersistedModeIsOff() = runBlocking {
        fixture("OFF", realAutomation = true) { _, chat, attempts, executions ->
            delay(700); assertEquals(0, executions.get()); assertEquals(0, attempts.get())
            assertEquals(0, chat.onChatSent.subscriptionCount.value)
        }
    }
}
