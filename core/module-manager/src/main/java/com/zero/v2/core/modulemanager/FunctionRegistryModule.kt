package com.zero.v2.core.modulemanager

import com.zero.v2.core.bus.ChatBus
import com.zero.v2.core.common.*
import com.zero.v2.core.moduleapi.*
import com.zero.v2.core.trace.AgentTraceEvent
import com.zero.v2.core.trace.AgentTraceManager
import com.zero.v2.core.trace.NodeType
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 函数注册中心模块。
 *
 * 对标 Alife 的 XmlFunctionCaller + XmlHandlerTable。
 * 收集所有模块注册的 FC 函数定义，生成 LLM tools 数组，执行和分发函数调用。
 *
 * v2.1: delay querying — getAllDefinitions() 在每次 buildSystemPrompt 时延迟调用，
 *       只返回已注册的 Map，不依赖 ModuleLifecycleManager（避免了循环依赖）。
 *
 * v2.2: 并发执行 — executeBatch() 将无显式依赖的 tool_calls 通过 coroutineScope + async 并行执行。
 *       dependsOn 声明的依赖自动保证执行顺序。
 *
 * 单次与批量调用统一进入 execute(ToolCall)/executeInternal。
 * 普通工具的旧确认分级已退役；系统权限和业务参数由执行端校验，模块发布策略由部署域负责。
 */
@Singleton
class FunctionRegistryModule @Inject constructor(
    private val chatBus: ChatBus,
    private val traceManager: AgentTraceManager,
    private val executionGuard: javax.inject.Provider<ToolExecutionGuard>,
) : IZeroModule, ToolRuntime, ToolRegistrar {

    override val metadata = ModuleMetadata(
        id = "function_registry",
        displayName = "函数注册中心",
        description = "FC 函数注册、分发执行、并发调度、安全拦截",
        category = ModuleCategory.CORE,
        launchOrder = -50
    )

    private val _lifecycle = MutableStateFlow(ModuleLifecycle.UNINITIALIZED)
    override val lifecycle = _lifecycle.asStateFlow()
    override var isEnabled: Boolean = true
    override val requiredPermissions: List<String> = emptyList()
    override val config = object : ModuleConfigBase() {
        override fun toJson() = "{}"
        override fun fromJson(json: String) {}
        override fun defaultConfig() = this
    }

    /** 已注册函数：name -> FunctionEntry */
    @Volatile private var registry: Map<String, FunctionEntry> = emptyMap()
    private val contributionNames = mutableMapOf<String, Set<String>>()

    override suspend fun onAwake() { _lifecycle.value = ModuleLifecycle.AWAKE }
    override suspend fun onStart() { _lifecycle.value = ModuleLifecycle.STARTED }
    override suspend fun onDestroy() {
        synchronized(this) { registry = emptyMap(); contributionNames.clear() }
        _lifecycle.value = ModuleLifecycle.DESTROYED
    }
    override suspend fun onConfigChanged(newConfig: ModuleConfigBase) {}

    override suspend fun checkAvailability(): Boolean = true
    override fun markDisabled() { _lifecycle.value = ModuleLifecycle.DISABLED }

    // ═══════════════════════════════════════════════════════
    // 注册
    // ═══════════════════════════════════════════════════════

    /**
     * 注册 FC 函数（兼容重载 — 未声明权限时默认 AUTO）。
     *
     * v2.3: 既有调用方签名不变；Step 2.4 迁移各工具模块到带 permission 的重载后可移除。
     */
    override fun register(
        definition: FunctionDefinition,
        sourceModuleId: String,
        handler: suspend (Map<String, Any?>) -> FunctionResult
    ) = register(definition, sourceModuleId, ActionPermission.AUTO, handler)

    /**
     * 注册 FC 函数。
     *
     * v2.3: permission — 统一行动权限（DESIGN D9/D24）。
     *       permission 置于 handler 之前，调用方可继续使用尾随 lambda：
     *       `register(def, moduleId, permission = ActionPermission.CONFIRM) { ... }`
     */
    @Synchronized
    override fun register(
        definition: FunctionDefinition,
        sourceModuleId: String,
        permission: ActionPermission,
        handler: suspend (Map<String, Any?>) -> FunctionResult
    ) {
        registry = registry + (definition.name to FunctionEntry(
            definition = definition,
            sourceModuleId = sourceModuleId,
            handler = handler,
            permission = permission
        ))
    }

    @Synchronized
    override fun unregister(name: String) { registry = registry - name }

    /**
     * F1：声明式工具注册——读取 [module] 的 [ToolContributor] 声明并集中注册。
     * 非 ToolContributor 模块返回 0。幂等：重复调用覆盖同名条目。
     * 落实"可选能力声明由注册器读取"（迭代总纲§3），替代分散的 registerFunctions() 主动注册。
     */
    @Synchronized
    override fun registerContributions(module: IZeroModule): Int {
        val contributor = module as? ToolContributor ?: return 0
        val declared = contributor.contributions()
        val names = declared.map { it.definition.name }.toSet()
        require(names.size == declared.size) { "Duplicate tool declarations" }
        val owner = module.metadata.id
        val next = registry.toMutableMap()
        contributionNames[owner].orEmpty().forEach { name ->
            if (next[name]?.sourceModuleId == owner) next.remove(name)
        }
        declared.forEach { c ->
            require(next[c.definition.name]?.sourceModuleId.let { it == null || it == owner }) {
                "Tool name belongs to another module"
            }
            next[c.definition.name] = FunctionEntry(c.definition, owner, c.handler, c.permission)
        }
        registry = next.toMap()
        contributionNames[owner] = names
        return declared.size
    }

    /** 获取所有已注册函数定义（延迟查询 — v2.1 修复循环依赖） */
    override fun getAllDefinitions(): List<FunctionDefinition> {
        return registry.values.map { it.definition }
    }

    /** 获取指定模块注册的函数条目（v2.4: 模块详情 UI 权限标签展示用） */
    override fun getEntriesByModule(moduleId: String): List<FunctionEntry> {
        return registry.values.filter { it.sourceModuleId == moduleId }
    }

    // ═══════════════════════════════════════════════════════
    // 执行
    // ═══════════════════════════════════════════════════════

    override suspend fun execute(name: String, arguments: Map<String, Any?>): FunctionResult {
        val entry = registry[name]
            ?: return FunctionResult.failure("未知函数: $name")

        return executeInternal(entry, arguments, FunctionCallIdentity.create())
    }

    /** 执行带模型 call id 的工具调用，保留该 id 贯穿 start/result/trace。 */
    override suspend fun execute(toolCall: ToolCall): FunctionResult {
        val entry = registry[toolCall.name]
            ?: return FunctionResult.failure("未知函数: ${toolCall.name}")
        return executeInternal(
            entry,
            parseArguments(toolCall.arguments),
            FunctionCallIdentity.create(toolCall.id)
        )
    }

    /**
     * 批量执行（v2.2：并发优化）。
     *
     * 策略：
     *   1. 将所有 ToolCall 分为独立调用（dependsOn 为空）和有依赖调用
     *   2. 独立调用通过 coroutineScope + async 并行执行
     *   3. 有依赖调用在所有独立调用完成后串行执行
     */
    override suspend fun executeBatch(toolCalls: List<ToolCall>): List<FunctionResult> {
        val dag = planFunctionCallDag(toolCalls, registry.mapValues { it.value.definition })
        val plan = dag.plan ?: return toolCalls.map {
            FunctionResult.failure("工具调用依赖图无效: ${dag.error}")
        }

        val results = arrayOfNulls<FunctionResult>(toolCalls.size)
        for (wave in plan.waves) {
            coroutineScope {
                wave.map { index ->
                    async { index to execute(toolCalls[index]) }
                }.map { deferred ->
                    val (index, result) = deferred.await()
                    results[index] = result
                }
            }
        }
        return results.map { it ?: FunctionResult.failure("工具调用未产生结果") }
    }

    private suspend fun executeInternal(
        entry: FunctionEntry,
        arguments: Map<String, Any?>,
        identity: FunctionCallIdentity
    ): FunctionResult {
        if (!runtimeState().operational) {
            return FunctionResult.failure("函数注册中心当前不可用: ${lifecycle.value}")
        }
        val startTime = System.currentTimeMillis()
        traceManager.record(
            AgentTraceEvent(
                traceId = identity.traceId,
                nodeType = NodeType.TOOL_CALL_START,
                sourceModule = entry.sourceModuleId,
                summary = "执行: ${entry.definition.name} callId=${identity.callId}",
                callId = identity.callId,
                functionName = entry.definition.name
            )
        )

        chatBus.onToolCallStart.emit(ToolCallStartEvent(
            name = entry.definition.name,
            arguments = arguments,
            callId = identity.callId
        ))

        return try {
            val result = executionGuard.get().executeForModule(entry.sourceModuleId, entry.definition.name) {
                entry.handler(arguments)
            }
            val duration = System.currentTimeMillis() - startTime

            traceManager.record(
                AgentTraceEvent(
                    traceId = identity.traceId,
                    nodeType = NodeType.TOOL_CALL_RESULT,
                    sourceModule = entry.sourceModuleId,
                    summary = if (result.success) "完成: ${result.data.take(80)}" else "失败: ${result.errorMessage}",
                    durationMs = duration,
                    callId = identity.callId,
                    functionName = entry.definition.name
                )
            )

            chatBus.onToolCallResult.emit(ToolCallResultEvent(
                callId = identity.callId,
                name = entry.definition.name,
                success = result.success,
                data = result.data,
                errorMessage = result.errorMessage,
                durationMs = duration
            ))

            result
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            val result = FunctionResult.failure("${entry.definition.name} 执行异常: ${e.message}")
            traceManager.record(
                AgentTraceEvent(
                    traceId = identity.traceId,
                    nodeType = NodeType.ERROR,
                    sourceModule = entry.sourceModuleId,
                    summary = "执行异常: ${entry.definition.name} callId=${identity.callId}",
                    detail = e::class.java.name,
                    durationMs = System.currentTimeMillis() - startTime,
                    callId = identity.callId,
                    functionName = entry.definition.name
                )
            )
            chatBus.onToolCallResult.emit(ToolCallResultEvent(
                callId = identity.callId,
                name = entry.definition.name,
                success = false,
                data = "",
                errorMessage = e.message,
                durationMs = System.currentTimeMillis() - startTime
            ))
            result
        }
    }



    private fun parseArguments(argumentsJson: String): Map<String, Any?> {
        return try {
            val json = org.json.JSONObject(argumentsJson)
            json.keys().asSequence().associateWith { json.get(it) }
        } catch (_: Exception) {
            emptyMap()
        }
    }
}

/** 同一次工具调用贯穿 Bus 事件、Trace 和结果持久化的身份。 */
internal data class FunctionCallIdentity(
    val callId: String,
    val traceId: String
) {
    companion object {
        fun create(candidate: String? = null): FunctionCallIdentity {
            val callId = candidate?.takeIf { it.isNotBlank() }
                ?: "call_${UUID.randomUUID()}"
            return FunctionCallIdentity(callId, "fc_$callId")
        }
    }
}

