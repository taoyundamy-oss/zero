package com.zero.v2.app.acceptance

import androidx.lifecycle.ViewModelStore
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.zero.v2.app.ui.settings.AgentBehaviorViewModel
import com.zero.v2.core.bus.ChatBusImpl
import com.zero.v2.core.bus.SystemBusImpl
import com.zero.v2.core.common.TokenUsage
import com.zero.v2.core.data.db.ZeroDatabase
import com.zero.v2.core.data.entity.ModuleConfigEntity
import com.zero.v2.core.data.store.*
import com.zero.v2.core.trace.AgentTraceManager
import com.zero.v2.framework.memory.MemoryModuleConfig
import com.zero.v2.services.agent.AgentRuntime
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger
import org.json.JSONObject

class AgentModeAcceptanceTest {
    private class Fixture {
        val db = Room.inMemoryDatabaseBuilder(InstrumentationRegistry.getInstrumentation().targetContext, ZeroDatabase::class.java).build()
        val owner = ViewModelStore()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        var rejectWrite = false
        var rejectRead = false
        var rejectRuntime = false
        var gate: CompletableDeferred<Unit>? = null
        val entered = CompletableDeferred<Unit>()
        val real = db.moduleConfigDao()
        val store = acceptanceConfigStore(object : ModuleConfigDao by real {
            override suspend fun upsert(entity: ModuleConfigEntity) {
                entered.complete(Unit)
                gate?.await()
                check(!rejectWrite) { "isolated write rejection" }
                real.upsert(entity)
            }
            override suspend fun getByModuleId(moduleId: String): ModuleConfigEntity? {
                check(!rejectRead) { "isolated read rejection" }
                return real.getByModuleId(moduleId)
            }
        }, scope)
        val bus = ChatBusImpl()
        val trace = AgentTraceManager(db.agentTraceEventDao())
        val runtime = AgentRuntime(SystemBusImpl(), bus, trace, store, dagger.Lazy { error("No paid trigger expected") })
        lateinit var vm: AgentBehaviorViewModel
        suspend fun open() {
            real.upsert(ModuleConfigEntity("agent_behavior", "{\"mode\":\"OFF\",\"selfDriveOnTick\":false}"))
            vm = withContext(Dispatchers.Main) {
                AgentBehaviorViewModel(runtime.config, MemoryModuleConfig(), MutableStateFlow(TokenUsage()), store, trace,
                    { mode -> check(!rejectRuntime); if (mode == "OFF") runtime.stopLoop() else runtime.startLoop() })
                    .also { owner.put("agent", it) }
            }
            await { vm.state.value.behaviorLoaded || vm.state.value.behaviorError != null }
        }
        suspend fun await(condition: () -> Boolean) = withTimeout(3000) { while (!condition()) delay(10) }
        suspend fun mode(value: String) = withContext(Dispatchers.Main) { vm.setMode(value) }
        suspend fun storedMode() = JSONObject(real.getByModuleId("agent_behavior")!!.configJson).getString("mode")
        suspend fun close() {
            gate?.complete(Unit)
            withContext(Dispatchers.Main) { owner.clear() }
            runtime.onDestroy(); scope.cancel(); delay(100); db.close()
        }
    }

    @Test fun rejectedWriteLeavesOldModeAndDoesNotReplayThenExplicitRetryStarts() = runBlocking {
        val f = Fixture()
        try {
            f.open(); f.rejectWrite = true; f.mode("SMART")
            f.await { f.vm.state.value.behaviorError != null }
            assertEquals("OFF", f.vm.state.value.mode); assertEquals("OFF", f.storedMode())
            assertEquals(0, f.bus.onChatSent.subscriptionCount.value)
            f.rejectWrite = false; delay(700); assertEquals("OFF", f.storedMode())
            withContext(Dispatchers.Main) { f.vm.retryBehaviorSave() }
            f.await { f.bus.onChatSent.subscriptionCount.value > 0 }
            assertEquals("SMART", f.storedMode()); assertNull(f.vm.state.value.behaviorError)
        } finally { f.close() }
    }

    @Test fun runtimeFailureKeepsCommittedIntentAndSameModeRetryWorks() = runBlocking {
        val f = Fixture()
        try {
            f.open(); f.rejectRuntime = true; f.mode("SMART")
            f.await { f.vm.state.value.behaviorError != null }
            assertEquals("SMART", f.storedMode()); assertEquals("SMART", f.vm.state.value.mode)
            assertEquals(0, f.bus.onChatSent.subscriptionCount.value)
            f.rejectRuntime = false
            withContext(Dispatchers.Main) { f.vm.retryBehaviorSave() }
            f.await { f.bus.onChatSent.subscriptionCount.value > 0 }
            assertNull(f.vm.state.value.behaviorError)
        } finally { f.close() }
    }

    @Test fun readFailureCannotOverwriteStoredOffAndCanReload() = runBlocking {
        val f = Fixture()
        try {
            f.rejectRead = true; f.open(); f.mode("SMART")
            withContext(Dispatchers.Main) { f.vm.setSelfDriveOnTick(true); f.vm.setBudget(dailyTokenLimit = 50_000_000) }
            delay(100); assertEquals("OFF", f.storedMode()); assertFalse(f.vm.state.value.behaviorLoaded)
            f.rejectRead = false
            withContext(Dispatchers.Main) { f.vm.reloadBehavior() }
            f.await { f.vm.state.value.behaviorLoaded }
            assertEquals("OFF", f.vm.state.value.mode); assertNull(f.vm.state.value.behaviorError)
        } finally { f.close() }
    }

    @Test fun overlappingBehaviorChangesCannotOverwriteModeCommit() = runBlocking {
        val f = Fixture()
        try {
            f.open(); f.gate = CompletableDeferred(); f.mode("SMART"); f.entered.await()
            withContext(Dispatchers.Main) { f.vm.setMode("OFF"); f.vm.setSelfDriveOnTick(true); f.vm.setBudget(dailyTokenLimit = 50_000_000) }
            assertEquals("OFF", f.storedMode()); assertEquals("OFF", f.vm.state.value.mode)
            f.gate!!.complete(Unit); f.await { !f.vm.state.value.behaviorSaving }
            assertEquals("SMART", f.storedMode())
            val obj = JSONObject(f.real.getByModuleId("agent_behavior")!!.configJson)
            assertFalse(obj.getBoolean("selfDriveOnTick")); assertEquals(20_000_000L, obj.getLong("dailyTokenLimit"))
        } finally { f.close() }
    }

    @Test fun ownerClearedDuringCommitCompletesStoredAndRuntimeIntentTogether() = runBlocking {
        val f = Fixture()
        try {
            f.open(); f.gate = CompletableDeferred(); f.mode("SMART"); f.entered.await()
            withContext(Dispatchers.Main) { f.owner.clear() }
            f.gate!!.complete(Unit); f.await { f.bus.onChatSent.subscriptionCount.value > 0 }
            assertEquals("SMART", f.storedMode()); assertEquals("SMART", f.vm.state.value.mode)
        } finally { f.close() }
    }

    @Test fun offStopsRealLoopAndAlwaysOnRestartsIt() = runBlocking {
        val f = Fixture()
        try {
            f.open(); f.mode("SMART"); f.await { f.bus.onChatSent.subscriptionCount.value > 0 && !f.vm.state.value.behaviorSaving }
            f.mode("OFF"); f.await { f.bus.onChatSent.subscriptionCount.value == 0 && !f.vm.state.value.behaviorSaving }
            assertEquals("OFF", f.storedMode())
            f.mode("ALWAYS_ON"); f.await { f.bus.onChatSent.subscriptionCount.value > 0 }
            assertEquals("ALWAYS_ON", f.storedMode())
        } finally { f.close() }
    }

    @Test fun budgetBoundsAndInvalidInputsDoNotChangeModeOrStoreNonFiniteNumbers() = runBlocking {
        val f = Fixture()
        try {
            f.open()
            withContext(Dispatchers.Main) { f.vm.setBudget(dailyTokenLimit = 1, monthlyCostLimitUsd = 100f) }
            f.await { !f.vm.state.value.behaviorSaving }
            val before = f.real.getByModuleId("agent_behavior")!!.configJson
            val obj = JSONObject(before)
            assertEquals(8_000_000L, obj.getLong("dailyTokenLimit")); assertEquals(50.0, obj.getDouble("monthlyCostLimitUsd"), 0.0)
            withContext(Dispatchers.Main) { f.vm.setBudget(monthlyCostLimitUsd = Float.NaN); f.vm.setMode("invalid") }
            delay(100); assertEquals(before, f.real.getByModuleId("agent_behavior")!!.configJson)
            assertEquals("OFF", f.vm.state.value.mode)
        } finally { f.close() }
    }

    @Test fun persistedOffToSmartMustActuallyRestartRuntimeAfterCommit() = runBlocking {
        val db=Room.inMemoryDatabaseBuilder(InstrumentationRegistry.getInstrumentation().targetContext,ZeroDatabase::class.java).build()
        val owner=ViewModelStore();val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
        val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>();val triggers=AtomicInteger()
        val real=db.moduleConfigDao()
        val store=acceptanceConfigStore(object:ModuleConfigDao by real {
            override suspend fun upsert(entity:ModuleConfigEntity) {
                if(entity.moduleId=="agent_behavior" && entity.configJson.contains("SMART")) { entered.complete(Unit);release.await() }
                real.upsert(entity)
            }
        },scope)
        val bus=ChatBusImpl();val trace=AgentTraceManager(db.agentTraceEventDao())
        val runtime=AgentRuntime(SystemBusImpl(),bus,trace,store,dagger.Lazy { triggers.incrementAndGet();error("No paid trigger expected") })
        try {
            real.upsert(ModuleConfigEntity("agent_behavior","{\"mode\":\"OFF\",\"selfDriveOnTick\":false}"))
            val vm=withContext(Dispatchers.Main) { AgentBehaviorViewModel(runtime.config,MemoryModuleConfig(),MutableStateFlow(TokenUsage()),store,trace,
                {mode->if(mode=="OFF")runtime.stopLoop() else runtime.startLoop() }).also { owner.put("agent",it) } }
            withTimeout(3000) { while(vm.state.value.mode!="OFF")delay(10) }
            withContext(Dispatchers.Main) { vm.setMode("SMART") };entered.await()
            delay(200);release.complete(Unit)
            var running=false
            repeat(100) { if(bus.onChatSent.subscriptionCount.value>0)running=true else delay(10) }
            assertTrue("Changing OFF to SMART must start the actual runtime after configuration is saved",running)
            assertEquals("SMART",vm.state.value.mode);assertEquals(0,triggers.get())
        } finally {
            release.complete(Unit);withContext(Dispatchers.Main) { owner.clear() };runtime.onDestroy();scope.cancel();delay(100);db.close()
        }
    }
}
