package com.zero.v2.app.acceptance

import androidx.lifecycle.ViewModelStore
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.zero.v2.app.ui.settings.AgentBehaviorViewModel
import com.zero.v2.core.common.TokenUsage
import com.zero.v2.core.data.db.ZeroDatabase
import com.zero.v2.core.data.entity.ModuleConfigEntity
import com.zero.v2.core.data.store.*
import com.zero.v2.core.trace.AgentTraceManager
import com.zero.v2.framework.memory.MemoryModuleConfig
import com.zero.v2.services.agent.AgentRuntimeConfig
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Test

class AgentParameterAcceptanceTest {
    private suspend fun confirmedChange(key: String, reject: Boolean = false, cancelOwner: Boolean = false) {
        val db = Room.inMemoryDatabaseBuilder(InstrumentationRegistry.getInstrumentation().targetContext, ZeroDatabase::class.java).build()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO); val owner = ViewModelStore()
        val real = db.moduleConfigDao(); val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val report = AgentRuntimeConfig(); val memory = MemoryModuleConfig()
        report.randomOffsetSec = 29; memory.graphExtractionThreshold = 77
        val store = acceptanceConfigStore(object : ModuleConfigDao by real {
            override suspend fun upsert(entity: ModuleConfigEntity) {
                if (entity.moduleId == key) { entered.complete(Unit); release.await() }
                real.upsert(entity)
            }
        }, scope)
        try {
            real.upsert(ModuleConfigEntity("agent_behavior", "{\"mode\":\"OFF\"}"))
            val original = if (key == "agent_runtime") report.toJson() else memory.toJson()
            real.upsert(ModuleConfigEntity(key, original))
            if (reject) withContext(Dispatchers.IO) {
                db.openHelper.writableDatabase.execSQL("CREATE TRIGGER isolated_parameter_reject BEFORE INSERT ON module_configs WHEN NEW.module_id='$key' BEGIN SELECT RAISE(ABORT, 'isolated parameter rejection'); END")
            }
            val vm = withContext(Dispatchers.Main) { AgentBehaviorViewModel(report, memory, MutableStateFlow(TokenUsage()), store,
                AgentTraceManager(db.agentTraceEventDao()), {}).also { owner.put("parameter", it) } }
            withTimeout(3000) { while (!vm.state.value.behaviorLoaded) delay(10) }
            withContext(Dispatchers.Main) {
                if (key == "agent_runtime") vm.setReportParam("baseIntervalSec", 1234) else vm.setMemoryParam("reflectionHour", 9)
            }
            withTimeout(3000) { entered.await() }
            assertEquals("Uncommitted parameter must not change the live configuration", if (key == "agent_runtime") 90 else 2,
                if (key == "agent_runtime") report.baseIntervalSec else memory.reflectionHour)
            assertEquals("Uncommitted parameter must not be presented as saved", if (key == "agent_runtime") 90 else 2,
                if (key == "agent_runtime") vm.state.value.baseIntervalSec else vm.state.value.reflectionHour)
            if (cancelOwner) withContext(Dispatchers.Main) { owner.clear() }
            release.complete(Unit)
            if (reject) {
                withTimeout(3000) { while (vm.state.value.parameterError == null) delay(10) }
                assertNull(vm.state.value.parameterMessage)
                assertEquals(original, real.getByModuleId(key)!!.configJson)
                assertEquals(if (key == "agent_runtime") 90 else 2, if (key == "agent_runtime") report.baseIntervalSec else memory.reflectionHour)
                withContext(Dispatchers.IO) { db.openHelper.writableDatabase.execSQL("DROP TRIGGER isolated_parameter_reject") }
                delay(700); assertEquals("Rejected candidate must not apply through background retry", original, real.getByModuleId(key)!!.configJson)
                withContext(Dispatchers.Main) { vm.retryParameterSave() }
            }
            withTimeout(3000) { while ((if (key == "agent_runtime") report.baseIntervalSec else memory.reflectionHour) != (if (key == "agent_runtime") 1234 else 9)) delay(10) }
            withTimeout(3000) { while (vm.state.value.parameterSaving) delay(10) }
            assertNotNull(real.getByModuleId(key))
            assertEquals(29, report.randomOffsetSec); assertEquals(77, memory.graphExtractionThreshold)
            assertNull(vm.state.value.parameterError)
            if (cancelOwner) assertNull(vm.state.value.parameterMessage) else assertEquals("设置已保存", vm.state.value.parameterMessage)
        } finally { release.complete(Unit); withContext(Dispatchers.Main) { owner.clear() }; scope.cancel(); delay(100); db.close() }
    }
    @Test fun reportParameterPublishesOnlyAfterConfirmedWrite() = runBlocking { confirmedChange("agent_runtime") }
    @Test fun memoryParameterPublishesOnlyAfterConfirmedWrite() = runBlocking { confirmedChange("memory") }
    @Test fun reportWriteRejectedHasFeedbackNoReplayAndExplicitRetry() = runBlocking { confirmedChange("agent_runtime", reject = true) }
    @Test fun memoryWriteRejectedHasFeedbackNoReplayAndExplicitRetry() = runBlocking { confirmedChange("memory", reject = true) }
    @Test fun reportOwnerClearedDuringCommitHasNoFalseSuccessAndConfigStaysConsistent() = runBlocking { confirmedChange("agent_runtime", cancelOwner = true) }
    @Test fun memoryOwnerClearedDuringCommitHasNoFalseSuccessAndConfigStaysConsistent() = runBlocking { confirmedChange("memory", cancelOwner = true) }
}
