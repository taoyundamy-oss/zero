package com.zero.v2.core.modulemanager

import com.zero.v2.core.bus.ChatBusImpl
import com.zero.v2.core.bus.SystemBusImpl
import com.zero.v2.core.data.store.ModuleConfigStore
import com.zero.v2.core.moduleapi.ModuleCategory
import com.zero.v2.core.moduleapi.ModuleConfigBase
import com.zero.v2.core.moduleapi.ModuleDescriptor
import com.zero.v2.core.moduleapi.ModuleMetadata
import com.zero.v2.core.moduleapi.IZeroModule
import com.zero.v2.core.moduleapi.ToolRegistrar
import com.zero.v2.core.trace.AgentTraceManager
import java.lang.reflect.Proxy
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class ModuleStartupFailureTest {
    @Test fun `healthy module still starts once`() = runBlocking {
        val module = ProbeModule()
        manager(module).initializeAll()
        assertEquals(1, module.starts)
    }

    @Test fun `failed awake must not run start`() = runBlocking {
        val module = ProbeModule(failAwake = true)
        manager(module).initializeAll()
        assertEquals(0, module.starts)
    }

    @Test fun `failed tool registration must not run start on initial startup or retry`() = runBlocking {
        val module = ProbeModule()
        val manager = manager(module, failRegistration = true)
        manager.initializeAll()
        assertEquals(0, module.starts)
        manager.reEnable(module.metadata.id)
        assertEquals(0, module.starts)
    }

    private fun manager(module: ProbeModule, failRegistration: Boolean = false): ModuleLifecycleManager {
        val chat = ChatBusImpl()
        val trace = AgentTraceManager(dao())
        val registry = testRegistry(chat, trace)
        val registrar = object : ToolRegistrar by registry {
            override fun registerContributions(module: IZeroModule): Int {
                check(!failRegistration) { "registration failed" }
                return registry.registerContributions(module)
            }
        }
        return ModuleLifecycleManager(setOf(module), chat, ModuleConfigStore(dao()), trace, registry, registrar)
    }

    private class ProbeModule(private val failAwake: Boolean = false) : ModuleDescriptor() {
        var starts = 0
        override val metadata = ModuleMetadata("startup_probe", "Startup probe", "test", ModuleCategory.SYSTEM)
        override val requiredPermissions = emptyList<String>()
        override val config = object : ModuleConfigBase() {
            override fun toJson() = "{}"
            override fun fromJson(json: String) = Unit
            override fun defaultConfig() = this
        }
        override suspend fun onAwake() { check(!failAwake) { "awake failed" } }
        override suspend fun onStart() { starts++ }
    }

    private inline fun <reified T> dao(): T = Proxy.newProxyInstance(
        T::class.java.classLoader, arrayOf(T::class.java),
    ) { _, method, _ -> if (method.name == "getByModuleId") null else Unit } as T
}
