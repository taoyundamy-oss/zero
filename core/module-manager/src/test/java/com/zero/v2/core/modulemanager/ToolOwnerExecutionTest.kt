package com.zero.v2.core.modulemanager

import com.zero.v2.core.bus.ChatBusImpl
import com.zero.v2.core.common.FunctionDefinition
import com.zero.v2.core.common.FunctionResult
import com.zero.v2.core.common.ToolCall
import com.zero.v2.core.data.store.ModuleConfigStore
import com.zero.v2.core.moduleapi.*
import com.zero.v2.core.trace.AgentTraceManager
import java.lang.reflect.Proxy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ToolOwnerExecutionTest {
    @Test fun `disabled owner blocks all entry points including dynamic registration`() = runBlocking {
        val (registry, manager, owner) = fixture()
        manager.initializeAll()
        registry.register(FunctionDefinition("late", "late"), owner.metadata.id) { owner.calls++; FunctionResult.success("late") }
        manager.setUserEnabled(owner.metadata.id, false)
        assertFalse(registry.execute("probe", emptyMap()).success)
        assertFalse(registry.execute(ToolCall("one", "probe", "{}")).success)
        assertFalse(registry.executeBatch(listOf(ToolCall("batch", "late", "{}"))).single().success)
        assertEquals(0, owner.calls)
    }

    @Test fun `unexpected exception disables non core owner through lifecycle safeCall`() = runBlocking {
        val (registry, manager, owner) = fixture()
        manager.initializeAll()
        owner.error = IllegalStateException("probe failure")
        assertFalse(registry.execute("probe", emptyMap()).success)
        assertFalse(owner.isEnabled)
        assertEquals(ModuleLifecycle.DISABLED, owner.lifecycle.value)
        assertFalse(registry.execute("probe", emptyMap()).success)
        assertEquals(1, owner.calls)
    }

    @Test fun `cancellation is propagated without disabling owner`() = runBlocking {
        val (registry, manager, owner) = fixture()
        manager.initializeAll()
        owner.error = CancellationException("cancelled")
        assertTrue(runCatching { registry.execute("probe", emptyMap()) }.exceptionOrNull() is CancellationException)
        assertTrue(owner.isEnabled)
    }

    @Test fun `refresh removes retired declarations and keeps dynamic tools`() = runBlocking {
        val (registry, manager, owner) = fixture()
        manager.initializeAll()
        registry.register(FunctionDefinition("late", "late"), owner.metadata.id) { FunctionResult.success("late") }
        owner.toolName = "replacement"
        registry.registerContributions(owner)
        registry.registerContributions(owner)
        assertEquals(setOf("replacement", "late"), registry.getAllDefinitions().map { it.name }.toSet())
        assertFalse(registry.execute("probe", emptyMap()).success)
        assertTrue(registry.execute("replacement", emptyMap()).success)
        registry.unregister("late")
        assertFalse(registry.execute("late", emptyMap()).success)
    }

    @Test fun `destroyed and unknown owners never enter handlers`() = runBlocking {
        val (registry, manager, owner) = fixture()
        manager.initializeAll()
        owner.lifecycle.value = ModuleLifecycle.DESTROYED
        assertFalse(registry.execute("probe", emptyMap()).success)
        registry.register(FunctionDefinition("orphan", "orphan"), "missing") { owner.calls++; FunctionResult.success("bad") }
        assertFalse(registry.execute("orphan", emptyMap()).success)
        assertEquals(0, owner.calls)
    }

    @Test fun `core exception and business failure do not disable owner`() = runBlocking {
        val (registry, manager, owner) = fixture(ModuleCategory.CORE)
        manager.initializeAll()
        owner.error = IllegalStateException("core error")
        assertFalse(registry.execute("probe", emptyMap()).success)
        assertTrue(owner.isEnabled)
        owner.error = null
        registry.register(FunctionDefinition("business", "business"), owner.metadata.id) { FunctionResult.failure("unavailable") }
        assertFalse(registry.execute("business", emptyMap()).success)
        assertTrue(owner.isEnabled)
    }

    private fun fixture(category: ModuleCategory = ModuleCategory.SYSTEM): Triple<FunctionRegistryModule, ModuleLifecycleManager, Probe> {
        val bus = ChatBusImpl()
        val trace = AgentTraceManager(dao())
        lateinit var manager: ModuleLifecycleManager
        val registry = FunctionRegistryModule(bus, trace, javax.inject.Provider { manager })
        val owner = Probe(category)
        manager = ModuleLifecycleManager(setOf(owner), bus, ModuleConfigStore(dao()), trace, registry, registry)
        return Triple(registry, manager, owner)
    }

    private class Probe(category: ModuleCategory) : ModuleDescriptor(), ToolContributor {
        override val lifecycle = kotlinx.coroutines.flow.MutableStateFlow(ModuleLifecycle.UNINITIALIZED)
        override fun markDisabled() { isEnabled = false; lifecycle.value = ModuleLifecycle.DISABLED }
        var toolName = "probe"
        var calls = 0
        var error: Exception? = null
        override val metadata = ModuleMetadata("probe_owner", "Probe", "test", category)
        override val requiredPermissions = emptyList<String>()
        override val config = object : ModuleConfigBase() {
            override fun toJson() = "{}"
            override fun fromJson(json: String) = Unit
            override fun defaultConfig() = this
        }
        override fun contributions() = listOf(ToolContribution(FunctionDefinition(toolName, "probe"),
            com.zero.v2.core.common.ActionPermission.AUTO, noArgs = true) {
            calls++
            error?.let { throw it }
            FunctionResult.success("ok")
        })
    }

    private inline fun <reified T> dao(): T = Proxy.newProxyInstance(
        T::class.java.classLoader, arrayOf(T::class.java),
    ) { _, method, _ -> if (method.name == "getByModuleId") null else Unit } as T
}
