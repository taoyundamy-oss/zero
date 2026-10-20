package com.zero.v2.app.acceptance

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.zero.v2.app.background.observeAutonomousMode
import com.zero.v2.core.bus.ChatBusImpl
import com.zero.v2.core.bus.SystemBusImpl
import com.zero.v2.core.common.*
import com.zero.v2.core.data.db.ZeroDatabase
import com.zero.v2.core.data.entity.ModuleConfigEntity
import com.zero.v2.core.data.store.acceptanceConfigStore
import com.zero.v2.core.moduleapi.*
import com.zero.v2.core.modulemanager.ModuleLifecycleManager
import com.zero.v2.core.trace.AgentTraceManager
import com.zero.v2.services.agent.AgentRuntime
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/** Same foreground host mode collector, real Room and Runtime; model entry intercepted. */
class AutonomousHostAcceptanceTest {
    private suspend fun fixture(mode: String) = coroutineScope {
        val db = Room.inMemoryDatabaseBuilder(InstrumentationRegistry.getInstrumentation().targetContext, ZeroDatabase::class.java).build()
        val worker = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val store = acceptanceConfigStore(db.moduleConfigDao(), worker)
        val chat = ChatBusImpl(); val trace = AgentTraceManager(db.agentTraceEventDao())
        val attempts = AtomicInteger(); val stops = AtomicInteger()
        val runtime = AgentRuntime(SystemBusImpl(), chat, trace, store, dagger.Lazy { attempts.incrementAndGet(); error("No paid requests") })
        val catalog = object : FunctionCatalog {
            override fun getAllDefinitions() = emptyList<FunctionDefinition>()
            override fun getEntriesByModule(moduleId: String) = emptyList<FunctionEntry>()
        }
        val registrar = object : ToolRegistrar {
            override fun register(definition: FunctionDefinition, sourceModuleId: String, permission: ActionPermission, handler: suspend (Map<String, Any?>) -> FunctionResult) = Unit
            override fun unregister(name: String) = Unit
            override fun registerContributions(module: IZeroModule) = 0
        }
        val lifecycle = ModuleLifecycleManager(setOf(runtime), chat, store, trace, catalog, registrar)
        var host: Job? = null
        try {
            store.saveConfirmed("agent_behavior", "{\"mode\":\"$mode\",\"selfDriveOnTick\":true}")
            lifecycle.initializeAll()
            host = launch(Dispatchers.Default) { observeAutonomousMode(db.moduleConfigDao(), lifecycle, runtime) { stops.incrementAndGet() } }
            withTimeout(3000) { while (chat.onChatSent.subscriptionCount.value != 1 || attempts.get() != 1) delay(10) }
            repeat(3) { store.saveConfirmed("agent_behavior", "{\"mode\":\"$mode\",\"selfDriveOnTick\":true,\"dailyTokenLimit\":${20_000_000+it}}") }
            store.saveConfirmed("agent_behavior", "{\"mode\":\"${if (mode == "SMART") "ALWAYS_ON" else "SMART"}\",\"selfDriveOnTick\":true}")
            delay(400); assertEquals(1, attempts.get()); assertEquals(1, chat.onChatSent.subscriptionCount.value)
            store.saveConfirmed("agent_behavior", "{\"mode\":\"OFF\",\"selfDriveOnTick\":true}")
            withTimeout(3000) { while (stops.get() != 1 || chat.onChatSent.subscriptionCount.value != 0) delay(10) }
            host.cancelAndJoin()
            // A new foreground lifetime may resume, but OFF is never silently replaced with SMART.
            host = launch(Dispatchers.Default) { observeAutonomousMode(db.moduleConfigDao(), lifecycle, runtime) { stops.incrementAndGet() } }
            withTimeout(3000) { while (stops.get() != 2) delay(10) }
            assertEquals(1, attempts.get()); assertEquals(0, chat.onChatSent.subscriptionCount.value)
            host.cancelAndJoin()
            store.saveConfirmed("agent_behavior", "{\"mode\":\"$mode\",\"selfDriveOnTick\":true}")
            host = launch(Dispatchers.Default) { observeAutonomousMode(db.moduleConfigDao(), lifecycle, runtime) { stops.incrementAndGet() } }
            withTimeout(3000) { while (chat.onChatSent.subscriptionCount.value != 1) delay(10) }
            // Reopening in the same process keeps the previous cadence deadline, not an extra paid turn.
            delay(400); assertEquals(1, attempts.get())
            assertEquals(2, stops.get())
            assertTrue(lifecycle.setUserEnabled(runtime.metadata.id, false))
            withTimeout(3000) { while (stops.get() != 3 || chat.onChatSent.subscriptionCount.value != 0) delay(10) }
            assertEquals(1, attempts.get())
        } finally { host?.cancelAndJoin(); lifecycle.destroyAll(); worker.cancel(); delay(100); db.close() }
    }
    @Test fun smartHostRetainsSingleOwnerAndHonorsConfirmedOffAcrossRestarts() = runBlocking { fixture("SMART") }
    @Test fun alwaysOnHostRetainsSingleOwnerAndHonorsConfirmedOffAcrossRestarts() = runBlocking { fixture("ALWAYS_ON") }
}
