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
import com.zero.v2.services.module.AutomationModule
import com.zero.v2.services.module.ExecutionPlan
import com.zero.v2.services.module.PlanStep
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/** Actual dispatch/Room path; no personal tasks, alarms or provider calls. */
class AutomationDispatchAcceptanceTest {
    private suspend fun fixture(
        tool: suspend () -> FunctionResult,
        block: suspend (AutomationModule, ZeroDatabase) -> Unit,
    ) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context, ZeroDatabase::class.java).build()
        val worker = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val store = acceptanceConfigStore(db.moduleConfigDao(), worker)
        val chat = ChatBusImpl()
        val trace = AgentTraceManager(db.agentTraceEventDao())
        val runtime = AgentRuntime(SystemBusImpl(), chat, trace, store, dagger.Lazy { error("No provider permitted") })
        val preferenceName = "a8_isolated_dispatch_budget"
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
        try {
            db.automationDao().insert(AutomationEntity("isolated-dispatch", "隔离调度", "本地测试",
                ExecutionPlan("isolated goal", listOf(PlanStep(1, "local echo", "isolated_echo"))).toJson(),
                scheduleType = "INTERVAL", scheduleValue = "3600", nextRunAt = 0))
            module.onAwake()
            block(module, db)
        } finally {
            module.onDestroy(); runtime.stopLoop(); worker.cancel(); delay(100)
            db.close(); context.deleteSharedPreferences(preferenceName)
        }
    }

    @Test fun alarmAndTickDispatchSameDueTaskOnlyOnce() = runBlocking {
        val executions = AtomicInteger()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        fixture({ executions.incrementAndGet(); entered.complete(Unit); release.await(); FunctionResult.success("local") }) { module, db ->
            coroutineScope {
                val first = launch(Dispatchers.IO) { module.execute() }
                withTimeout(3000) { entered.await() }
                val second = launch(Dispatchers.IO) { module.execute() }
                try { delay(500) } finally { release.complete(Unit) }
                withTimeout(5000) { joinAll(first, second) }
            }
            assertEquals("Alarm and heartbeat must not run the same due task twice", 1, executions.get())
            assertEquals(1, requireNotNull(db.automationDao().getById("isolated-dispatch")).runCount)
            assertTrue(module.runProgress.value?.done == true)
        }
    }

    @Test fun toolCancellationDoesNotAdvanceScheduleOrPublishSuccess() = runBlocking {
        val executions = AtomicInteger()
        fixture({
            if (executions.incrementAndGet() == 1) throw CancellationException("isolated tool cancellation")
            FunctionResult.success("retry succeeded")
        }) { module, db ->
            var cancelled = false
            try { module.execute() } catch (_: CancellationException) { cancelled = true }
            assertTrue("Cancellation must propagate to dispatch owner", cancelled)
            val saved = requireNotNull(db.automationDao().getById("isolated-dispatch"))
            assertEquals(0, saved.runCount)
            assertEquals(0L, saved.nextRunAt)
            assertNull(saved.lastRunAt)
            assertFalse(module.runProgress.value?.done == true)
            withTimeout(3000) { module.execute() }
            assertEquals("Cancellation must release dispatch ownership for a subsequent attempt", 1,
                requireNotNull(db.automationDao().getById("isolated-dispatch")).runCount)
            assertTrue(module.runProgress.value?.done == true)
        }
    }
}
