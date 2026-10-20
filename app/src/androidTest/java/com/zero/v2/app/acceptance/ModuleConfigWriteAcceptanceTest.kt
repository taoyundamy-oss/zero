package com.zero.v2.app.acceptance

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.zero.v2.core.data.db.ZeroDatabase
import com.zero.v2.core.data.store.ModuleConfigStore
import com.zero.v2.core.moduleapi.*
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** SQL rejection in a private in-memory database; no personal configuration is altered. */
class ModuleConfigWriteAcceptanceTest {
    @Test fun debounceRemainsBoundToOriginalModuleAfterNavigation() = runBlocking {
        val inst = InstrumentationRegistry.getInstrumentation()
        val context = inst.targetContext
        val database = Room.inMemoryDatabaseBuilder(context, ZeroDatabase::class.java).build()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val store = ModuleConfigStore(database.moduleConfigDao(), scope)
        val first = Module("a8_first_${System.currentTimeMillis()}")
        val second = Module("a8_second_${System.currentTimeMillis()}")
        val entry = EntryPointAccessors.fromApplication(context, RegenerationAcceptanceEntryPoint::class.java)
        val owner = entry.moduleDetailAcceptanceFactory().isolated(setOf(first, second), store)
        val owners = androidx.lifecycle.ViewModelStore().apply { put("detail", owner) }
        try {
            store.saveConfirmed(first.metadata.id, first.config.toJson())
            store.saveConfirmed(second.metadata.id, second.config.toJson())
            inst.runOnMainSync { owner.load(first.metadata.id) }
            withTimeout(10_000) {
                while (owner.state.value.module == null) delay(25)
            }
            inst.runOnMainSync { owner.updateConfigField("count", "7"); owner.load(second.metadata.id) }
            withTimeout(10_000) {
                while (owner.state.value.module?.metadata?.id != second.metadata.id) delay(25)
            }
            inst.runOnMainSync { owner.save() }
            delay(900)
            assertEquals("Debounce must not write the next page's module", 3, second.config.count)
            assertEquals(7, first.config.count)
            assertEquals(3, JSONObject(requireNotNull(store.get(second.metadata.id))).getInt("count"))
            assertEquals(7, JSONObject(requireNotNull(store.get(first.metadata.id))).getInt("count"))
        } finally {
            inst.runOnMainSync { owners.clear() }
            scope.coroutineContext[Job]!!.cancelAndJoin()
            database.close()
        }
    }
    class Config : ModuleConfigBase() {
        @ConfigField(label = "数量", fieldType = ConfigFieldType.NUMBER, min = 0.0, max = 100.0)
        var count = 3
        override fun toJson() = JSONObject().put("count", count).toString()
        override fun fromJson(json: String) { count = JSONObject(json).getInt("count") }
        override fun defaultConfig() = Config()
    }
    class Module(id: String, name: String = "隔离配置", description: String = "验收夹具") : IZeroModule {
        override val metadata = ModuleMetadata(id, name, description, ModuleCategory.SYSTEM)
        override val lifecycle = MutableStateFlow(ModuleLifecycle.STARTED)
        override var isEnabled = true
        override val config = Config()
        override val requiredPermissions = emptyList<String>()
        var changes = 0
        override suspend fun onAwake() {}
        override suspend fun onStart() {}
        override suspend fun onDestroy() {}
        override suspend fun onConfigChanged(newConfig: ModuleConfigBase) { changes++ }
        override suspend fun checkAvailability() = true
        override fun markDisabled() { lifecycle.value = ModuleLifecycle.DISABLED }
    }
    @Test fun rejectedWritePreservesRuntimeAndDoesNotQueueUnacceptedCandidate() = runBlocking {
        val inst = InstrumentationRegistry.getInstrumentation()
        val context = inst.targetContext
        val database = Room.inMemoryDatabaseBuilder(context, ZeroDatabase::class.java).build()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val store = ModuleConfigStore(database.moduleConfigDao(), scope)
        val module = Module("a8_config_${System.currentTimeMillis()}")
        val entry = EntryPointAccessors.fromApplication(context, RegenerationAcceptanceEntryPoint::class.java)
        val owner = entry.moduleDetailAcceptanceFactory().isolated(module, store)
        val owners = androidx.lifecycle.ViewModelStore().apply { put("detail", owner) }
        try {
            store.saveConfirmed(module.metadata.id, module.config.toJson())
            database.openHelper.writableDatabase.execSQL("""
                CREATE TRIGGER reject_a8_config BEFORE INSERT ON module_configs
                WHEN NEW.module_id='${module.metadata.id}'
                BEGIN SELECT RAISE(ABORT, 'isolated_write_rejected'); END
            """.trimIndent())
            inst.runOnMainSync { owner.load(module.metadata.id) }
            withTimeout(10_000) {
                while (owner.state.value.module == null) delay(25)
            }
            assertNotNull(owner.state.value.module)
            inst.runOnMainSync { owner.updateConfigField("count", "7"); owner.save() }
            delay(700)
            assertEquals("Rejected writes must not publish a runtime candidate", 3, module.config.count)
            assertEquals(3, JSONObject(requireNotNull(store.get(module.metadata.id))).getInt("count"))
            assertEquals(0, module.changes)
            database.openHelper.writableDatabase.execSQL("DROP TRIGGER reject_a8_config")
            delay(700)
            assertEquals("Rejected UI candidates must not be silently retried by the store", 3,
                JSONObject(requireNotNull(store.get(module.metadata.id))).getInt("count"))
            inst.runOnMainSync { owner.save() }
            delay(700)
            assertEquals(7, module.config.count)
            assertEquals(7, JSONObject(requireNotNull(store.get(module.metadata.id))).getInt("count"))
            assertEquals(1, module.changes)
        } finally {
            inst.runOnMainSync { owners.clear() }
            scope.coroutineContext[Job]!!.cancelAndJoin()
            database.close()
        }
    }
}
