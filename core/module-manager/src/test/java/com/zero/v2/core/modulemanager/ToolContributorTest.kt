package com.zero.v2.core.modulemanager

import com.zero.v2.core.moduleapi.ToolContributor
import com.zero.v2.core.moduleapi.ToolContribution

import com.zero.v2.core.bus.ChatBusImpl
import com.zero.v2.core.bus.SystemBusImpl
import com.zero.v2.core.common.ActionPermission
import com.zero.v2.core.common.FunctionDefinition
import com.zero.v2.core.common.FunctionResult
import com.zero.v2.core.data.dao.AgentTraceEventDao
import com.zero.v2.core.data.entity.AgentTraceEventEntity
import com.zero.v2.core.data.dao.RecentTraceRow
import com.zero.v2.core.moduleapi.ModuleCategory
import com.zero.v2.core.moduleapi.ModuleConfigBase
import com.zero.v2.core.moduleapi.ModuleDescriptor
import com.zero.v2.core.moduleapi.ModuleMetadata
import com.zero.v2.core.trace.AgentTraceManager
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * F1：ModuleDescriptor 基类 + ToolContributor 声明式注册 + 校验。
 * 验收（落地计划§2 F1）：样板模块在单测中完成生命周期与目录发现；
 * 无权限声明/无 schema/重复 name 被拒绝；既有模块不受影响。
 */
class ToolContributorTest {

    private fun newRegistry(): FunctionRegistryModule =
        testRegistry(ChatBusImpl(), AgentTraceManager(NoOpTraceDao))

    @Test fun `single and batch calls do not emit retired confirmation requests`() = runBlocking {
        val bus = SystemBusImpl()
        val registry = testRegistry(ChatBusImpl(), AgentTraceManager(NoOpTraceDao))
        registry.onAwake()
        registry.onStart()
        val events = mutableListOf<com.zero.v2.core.common.SecurityConfirmRequest>()
        val observer = kotlinx.coroutines.CoroutineScope(coroutineContext).launch(
            start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED,
        ) { bus.onSecurityConfirmRequest.collect { events.add(it) } }
        try {
            registry.register(FunctionDefinition("legacy", "test", emptyMap(),
                securityLevel = com.zero.v2.core.common.FunctionSecurity.SENSITIVE),
                "test", ActionPermission.CONFIRM) { FunctionResult.success("same") }
            val single = registry.execute("legacy", emptyMap())
            val batch = registry.executeBatch(listOf(com.zero.v2.core.common.ToolCall("id", "legacy", "{}"))).single()
            kotlinx.coroutines.yield()
            assertEquals(single.data, batch.data)
            assertTrue("Retired confirmation must not be emitted", events.isEmpty())
        } finally { observer.cancel() }
    }

    @Test fun `actual dispatcher propagates cancellation through every entry point`() = runBlocking {
        val registry = newRegistry()
        registry.onAwake()
        registry.onStart()
        registry.register(FunctionDefinition("cancel", "test", emptyMap()), "test") {
            throw kotlinx.coroutines.CancellationException("cancelled")
        }
        val call = com.zero.v2.core.common.ToolCall("cancel-id", "cancel", "{}")
        assertTrue(runCatching { registry.execute("cancel", emptyMap()) }.exceptionOrNull() is kotlinx.coroutines.CancellationException)
        assertTrue(runCatching { registry.execute(call) }.exceptionOrNull() is kotlinx.coroutines.CancellationException)
        assertTrue(runCatching { registry.executeBatch(listOf(call)) }.exceptionOrNull() is kotlinx.coroutines.CancellationException)
    }

    // ── 1. ModuleDescriptor 默认生命周期 ──

    @Test
    fun `moduleDescriptor defaults apply lifecycle and disable semantics`() = runBlocking {
        val mod = SampleToolModule()
        // 默认空实现不抛异常、不改变状态
        mod.onAwake(); mod.onStart(); mod.onConfigChanged(mod.config)
        assertTrue(mod.checkAvailability())
        // markDisabled 收敛 isEnabled + lifecycle
        mod.markDisabled()
        assertFalse(mod.isEnabled)
        assertEquals(com.zero.v2.core.moduleapi.ModuleLifecycle.DISABLED, mod.lifecycle.value)
    }

    // ── 2. ToolContributor 声明式注册 → 目录发现 ──

    @Test
    fun `toolContributor declarations appear in catalog`() = runBlocking {
        val registry = newRegistry()
        val mod = SampleToolModule()
        assertEquals(1, registry.registerContributions(mod))
        val names = registry.getAllDefinitions().map { it.name }
        assertTrue("sample_echo 应已注册", names.contains("sample_echo"))
        // 幂等：再注册不新增条目
        registry.registerContributions(mod)
        assertEquals(1, registry.getAllDefinitions().size)
    }

    @Test
    fun `non contributor module registers zero`() = runBlocking {
        val registry = newRegistry()
        val mod = NonContributorModule()
        assertEquals(0, registry.registerContributions(mod))
        assertTrue(registry.getAllDefinitions().isEmpty())
    }

    @Test
    fun `registered tool executes via dispatcher`() = runBlocking {
        val registry = newRegistry()
        registry.registerContributions(SampleToolModule())
        val result = registry.execute("sample_echo", mapOf("text" to "hi"))
        assertTrue(result.success)
        assertEquals("echo:hi", result.data)
    }

    // ── 3. RegistrationValidator.validateToolContributors ──

    @Test
    fun `validator accepts valid contributions`() {
        RegistrationValidator.validateToolContributors(listOf(SampleToolModule()))
    }

    @Test
    fun `validator rejects missing schema`() {
        val bad = SampleToolModule(schemaParams = emptyMap(), noArgs = false)
        assertThrows(IllegalArgumentException::class.java) {
            RegistrationValidator.validateToolContributors(listOf(bad))
        }
    }

    @Test
    fun `validator accepts noArgs tool with empty schema`() {
        val noArg = SampleToolModule(schemaParams = emptyMap(), noArgs = true, name = "sample_ping")
        RegistrationValidator.validateToolContributors(listOf(noArg))
    }

    @Test
    fun `validator rejects duplicate name within one contributor`() {
        val dup = SampleToolModule(duplicate = true)
        assertThrows(IllegalArgumentException::class.java) {
            RegistrationValidator.validateToolContributors(listOf(dup))
        }
    }

    // ═══ 样板模块（演示新模块最小接入路径） ═══

    /** 样板：继承 ModuleDescriptor（省空实现）+ 实现 ToolContributor（声明式工具）。 */
    private class SampleToolModule(
        val name: String = "sample_echo",
        val schemaParams: Map<String, Any?> = mapOf(
            "type" to "object",
            "properties" to mapOf("text" to mapOf("type" to "string")),
            "required" to listOf("text")
        ),
        val noArgs: Boolean = false,
        val duplicate: Boolean = false,
    ) : ModuleDescriptor(), ToolContributor {

        override val metadata = ModuleMetadata(
            id = "sample_tool",
            displayName = "样板工具模块",
            description = "F1 新模块接入样板",
            category = ModuleCategory.SYSTEM
        )
        override val requiredPermissions = emptyList<String>()
        override val config = object : ModuleConfigBase() {
            override fun toJson() = "{}"
            override fun fromJson(json: String) {}
            override fun defaultConfig() = this
        }

        override fun contributions(): List<ToolContribution> {
            val def = FunctionDefinition(
                name = name,
                description = "回显输入文本（F1 样板）",
                parameters = schemaParams
            )
            val list = mutableListOf(
                ToolContribution(
                    definition = def,
                    permission = ActionPermission.AUTO,
                    noArgs = noArgs,
                    handler = { args ->
                        val text = args["text"]?.toString() ?: ""
                        FunctionResult.success("echo:$text")
                    }
                )
            )
            if (duplicate) list += list.first().copy()
            return list
        }
    }

    /** 非 ToolContributor 模块——验证 registerContributions 返回 0，不影响既有路径。 */
    private class NonContributorModule : ModuleDescriptor() {
        override val metadata = ModuleMetadata("non_contrib", "非贡献", "", ModuleCategory.SYSTEM)
        override val requiredPermissions = emptyList<String>()
        override val config = object : ModuleConfigBase() {
            override fun toJson() = "{}"
            override fun fromJson(json: String) {}
            override fun defaultConfig() = this
        }
    }

    /** 最小 trace DAO——注册/执行路径不触发持久化，no-op 即可。 */
    private object NoOpTraceDao : AgentTraceEventDao {
        override suspend fun insert(event: AgentTraceEventEntity) {}
        override suspend fun getByTraceId(traceId: String): List<AgentTraceEventEntity> = emptyList()
        override suspend fun getByCallId(callId: String): List<AgentTraceEventEntity> = emptyList()
        override suspend fun getRecentTraces(limit: Int): List<RecentTraceRow> = emptyList()
        override suspend fun deleteOlderThan(before: Long): Int = 0
        override suspend fun count(): Long = 0
    }
}
