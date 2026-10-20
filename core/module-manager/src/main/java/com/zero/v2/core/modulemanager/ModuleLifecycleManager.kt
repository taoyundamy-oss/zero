package com.zero.v2.core.modulemanager

import com.zero.v2.core.moduleapi.FunctionCatalog
import com.zero.v2.core.moduleapi.ToolContributor
import com.zero.v2.core.moduleapi.ToolRegistrar
import com.zero.v2.core.moduleapi.ModuleRuntimeStateReader

import android.util.Log
import com.zero.v2.core.bus.ChatBus
import com.zero.v2.core.common.PokeEvent
import com.zero.v2.core.common.PokePriority
import com.zero.v2.core.moduleapi.ITimedModule
import com.zero.v2.core.moduleapi.IZeroModule
import com.zero.v2.core.moduleapi.ModuleCategory
import com.zero.v2.core.moduleapi.ModuleLifecycle
import com.zero.v2.core.moduleapi.ModuleRuntimeStateStore
import com.zero.v2.core.moduleapi.runtimeState
import com.zero.v2.core.trace.AgentTraceManager
import com.zero.v2.core.trace.NodeType
import dagger.hilt.android.scopes.ViewModelScoped
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 模块生命周期管理器 — Zero v2 的核心编排器。
 *
 * 对标 Alife 的 ModuleSystem + CharacterSystem。
 * 负责：
 * 1. 收集所有 IZeroModule（Hilt @IntoSet）
 * 2. 按 launchOrder 排序
 * 3. 统一的生命周期调度（onAwake → onStart → onTick → onDestroy）
 * 4. 崩溃隔离（safeCall）
 * 5. 按分类/ID 检索模块
 *
 * v2.2 崩溃自愈策略：
 *   - Core 模块崩溃 → 记录日志 + 通知用户（不自动禁用 — 系统无法工作）
 *   - 非 Core 模块崩溃 → 自动标记 DISABLED + Poke 通知 + 用户可手动重新启用
 *   - 同一模块连续崩溃 3 次 → 发出 URGENT 级别通知
 */
@Singleton
class ModuleLifecycleManager @Inject constructor(
    private val modules: Set<@JvmSuppressWildcards IZeroModule>,
    private val chatBus: ChatBus,
    private val configStore: com.zero.v2.core.data.store.ModuleConfigStore,
    private val traceManager: AgentTraceManager,
    private val functionCatalog: FunctionCatalog,
    private val toolRegistrar: ToolRegistrar
) : ModuleRuntimeStateReader, com.zero.v2.core.moduleapi.ToolExecutionGuard {
    companion object {
        private const val TAG = "ModuleLifecycleMgr"
        /** Core 模块连续崩溃次数阈值（超过此值发出 URGENT 通知） */
        private const val CORE_CRASH_THRESHOLD = 3
    }

    /** 按 launchOrder 排序的模块列表 */
    private val ordered: List<IZeroModule> by lazy {
        modules.sortedBy { it.metadata.launchOrder }
    }

    /** 按 ID 索引的模块查找表 */
    private val moduleMap: Map<String, IZeroModule> by lazy {
        modules.associateBy { it.metadata.id }
    }

    /** Re-read source-owner state at execution time; discovery snapshots are not authorization. */
    override suspend fun executeForModule(
        moduleId: String,
        operation: String,
        block: suspend () -> com.zero.v2.core.common.FunctionResult,
    ): com.zero.v2.core.common.FunctionResult {
        val module = moduleMap[moduleId]
            ?: return com.zero.v2.core.common.FunctionResult.failure("工具所属模块不存在: $moduleId")
        if (!module.runtimeState().operational) {
            return com.zero.v2.core.common.FunctionResult.failure("工具所属模块当前不可用: $moduleId")
        }
        return safeCall(module, "tool:$operation") {
            try {
                block()
            } catch (rejected: com.zero.v2.core.moduleapi.ModuleOperationRejectedException) {
                traceManager.record("operation_rejected", NodeType.ERROR, moduleId,
                    "module_operation_rejected: ${rejected.code.take(80)}")
                com.zero.v2.core.common.FunctionResult.failure(rejected.code)
            }
        }
            ?: com.zero.v2.core.common.FunctionResult.failure("工具执行异常，已记录模块状态: $moduleId")
    }

    /** 每个模块的崩溃计数器 */
    private val crashCount = ConcurrentHashMap<String, Int>()

    private val runtimeStateStore = ModuleRuntimeStateStore(
        modules.associate { it.metadata.id to it.runtimeState() }
    )
    /** 模块运行态统一观察入口。 */
    override val runtimeStates: StateFlow<Map<String, com.zero.v2.core.moduleapi.ModuleRuntimeState>> =
        runtimeStateStore.states

    /** 心跳驱动模块列表 */
    val timedModules: List<ITimedModule> by lazy {
        modules.filterIsInstance<ITimedModule>()
    }

    // ═══════════════════════════════════════════════════════
    // 生命周期调度
    // ═══════════════════════════════════════════════════════

    /**
     * 初始化所有模块（应用启动时调用一次）。
     * 按 launchOrder 依次执行 存量配置恢复(D51) → onAwake → onStart。
     */
    suspend fun initializeAll() {
        RegistrationValidator.validateModules(modules)
        val ready = mutableListOf<IZeroModule>()
        for (module in ordered) {
            // D51 启动配置恢复：存量 config 先于 onAwake 应用。此前全库唯一恢复挂载点在
            // ModuleDetailViewModel.load()——用户不打开模块详情页，模块整个进程周期都跑默认值
            // （实测：speech_tts 的存量 mimoApiKeyRef 迁移因此空转）。fromJson 幂等，与
            // UI 路径重复应用无副作用；崩溃走 safeCall 隔离。
            safeCall(module, "configRestore") {
                configStore.get(module.metadata.id)?.let { json ->
                    if (json.isNotBlank()) {
                        module.config.fromJson(json)
                        traceManager.record("cfg_${module.metadata.id}", NodeType.SYSTEM_EVENT,
                            module.metadata.id, "config_restored_at_start: chars=${json.length}")
                    }
                }
            }
            if (safeCall(module, "onAwake") { module.onAwake() } == null) continue
            // 声明式工具贡献由注册器集中读取（ToolContributor）。
            if (safeCall(module, "toolContributions") { toolRegistrar.registerContributions(module) } != null) {
                ready += module
            }
        }
        RegistrationValidator.validateToolContributors(ready)
        RegistrationValidator.validateFunctions(
            entries = modules.flatMap { module -> functionCatalog.getEntriesByModule(module.metadata.id) },
            moduleIds = modules.map { it.metadata.id }.toSet()
        )
        for (module in ready) {
            safeCall(module, "onStart") { module.onStart() }
        }
    }

    /**
     * 销毁所有模块（应用退出时调用一次）。
     * 按 launchOrder 逆序执行 onDestroy。
     */
    suspend fun destroyAll() {
        for (module in ordered.reversed()) {
            safeCall(module, "onDestroy") { module.onDestroy() }
        }
    }

    // ═══════════════════════════════════════════════════════
    // 崩溃隔离 (v2.2)
    // ═══════════════════════════════════════════════════════

    /**
     * 安全的模块调用包装。
     *
     * Core 模块崩溃 → 记录栈 + 通知用户 + 崩溃 ≥3 次发出 URGENT 通知。
     * 非 Core 模块崩溃 → 自动标记 DISABLED + 通知用户 + 可手动重新启用。
     *
     * @param module 目标模块
     * @param operation 操作名称（"onAwake", "onStart", "onTick", "onDestroy"）
     * @param block 要执行的代码块
     * @return 执行结果，异常时返回 null
     */
    suspend fun <T> safeCall(
        module: IZeroModule,
        operation: String,
        block: suspend () -> T
    ): T? {
        return try {
            block()
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            val count = crashCount.merge(module.metadata.id, 1, Int::plus) ?: 1
            val traceId = "crash_${module.metadata.id}_$count"

            Log.e(TAG, "[${module.metadata.id}] $operation 崩溃 (第${count}次)", e)

            traceManager.record(
                traceId = traceId,
                nodeType = NodeType.ERROR,
                sourceModule = module.metadata.id,
                summary = "$operation 崩溃 (第${count}次): ${e.message?.take(100)}",
                detail = e.stackTraceToString()
            )

            if (module.metadata.category == ModuleCategory.CORE) {
                handleCoreCrash(module, count)
            } else {
                handleNonCoreCrash(module, count)
            }

            null
        } finally {
            refreshRuntimeState(module)
        }
    }

    private fun refreshRuntimeState(module: IZeroModule) {
        runtimeStateStore.update(module.metadata.id, module.runtimeState())
    }

    /** Core 模块崩溃处理 — 记录日志 + 通知用户，不自动禁用 */
    private suspend fun handleCoreCrash(module: IZeroModule, count: Int) {
        if (count >= CORE_CRASH_THRESHOLD) {
            chatBus.onPokeSend.emit(
                PokeEvent(
                    message = "🚨 核心模块 ${module.metadata.displayName} 连续崩溃 $count 次。" +
                        "建议重启应用。",
                    source = "system",
                    priority = PokePriority.URGENT
                )
            )
        } else {
            chatBus.onPokeSend.emit(
                PokeEvent(
                    message = "⚠️ 核心模块 ${module.metadata.displayName} 发生异常 (第${count}次)，" +
                        "系统正在尝试恢复。",
                    source = "system",
                    priority = PokePriority.HIGH
                )
            )
        }
    }

    /** 非 Core 模块崩溃处理 — 自动禁用 + 通知用户 */
    private suspend fun handleNonCoreCrash(module: IZeroModule, count: Int) {
        // markDisabled() 更新生命周期；同步收敛用户开关，避免 UI 与执行列表仍将其视为启用。
        module.isEnabled = false
        module.markDisabled()
        chatBus.onPokeSend.emit(
            PokeEvent(
                message = "⚠️ ${module.metadata.displayName} 模块异常，已自动禁用。" +
                    "可在设置中重新启用（崩溃次数: $count）。",
                source = "system",
                priority = PokePriority.HIGH
            )
        )
    }

    // ═══════════════════════════════════════════════════════
    // 模块检索
    // ═══════════════════════════════════════════════════════

    /** 获取所有模块 */
    fun getAll(): List<IZeroModule> = ordered

    /** 按 ID 查找模块 */
    fun getById(id: String): IZeroModule? = moduleMap[id]

    /** 获取模块当前运行态；所有调用方应通过此入口读取状态。 */
    fun getRuntimeState(id: String): com.zero.v2.core.moduleapi.ModuleRuntimeState? =
        moduleMap[id]?.runtimeState()

    /** 执行前统一门禁：模块不存在或处于禁用/销毁态时不可调用。 */
    fun isOperational(id: String): Boolean = getRuntimeState(id)?.operational == true

    fun setUserEnabled(id: String, enabled: Boolean): Boolean {
        val module = moduleMap[id] ?: return false
        module.isEnabled = enabled
        refreshRuntimeState(module)
        return true
    }

    /** 按分类获取模块 */
    fun getByCategory(category: ModuleCategory): List<IZeroModule> =
        ordered.filter { it.metadata.category == category }

    /** 获取所有已启用模块 */
    fun getEnabled(): List<IZeroModule> =
        ordered.filter { it.runtimeState().operational }

    /** 获取启用的 Core 模块 */
    fun getCoreModules(): List<IZeroModule> =
        getByCategory(ModuleCategory.CORE).filter { it.runtimeState().operational }

    /** 按标签筛选模块 */
    fun getByTag(tag: String): List<IZeroModule> =
        ordered.filter { it.metadata.tags.contains(tag) }

    /**
     * 重试启用已禁用的模块。
     * 用户从设置中手动触发。
     */
    suspend fun reEnable(moduleId: String): Boolean {
        val module = getById(moduleId) ?: return false
        crashCount.remove(moduleId)
        module.isEnabled = true
        val awake = safeCall(module, "reEnable-onAwake") { module.onAwake() }
        val start = if (awake != null) {
            val registered = safeCall(module, "reEnable-toolContributions") { toolRegistrar.registerContributions(module) }
            if (registered != null) safeCall(module, "reEnable-onStart") { module.onStart() } else null
        } else null
        return awake != null && start != null && module.runtimeState().operational
    }
}
