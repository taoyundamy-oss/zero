package com.zero.v2.core.moduleapi

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 模块基类 — 为新模块提供 IZeroModule 的默认空实现，减少样板。
 *
 * 设计目标（迭代总纲§3 / 迭代开发设计§2.1）：
 *   - 不破坏既有 ABI：直接实现 IZeroModule 的存量模块不受影响，无需一次修改。
 *   - 新模块继承本类后只需提供 `metadata`、`config`、`requiredPermissions`，
 *     并按需 override `onAwake`/`onStart`/`onDestroy`/
 *     `onConfigChanged`/`checkAvailability`。
 *   - 静态工具用 `ToolContributor`（module-api）声明式贡献，
 *     由生命周期管理器在 onAwake 后统一注册。
 *
 * 生命周期由 ModuleLifecycleManager 统一驱动（safeCall 包裹）；
 * 本基类不自行调度，仅维护状态字段与禁用语义。
 */
abstract class ModuleDescriptor : IZeroModule {

    private val _lifecycle = MutableStateFlow(ModuleLifecycle.UNINITIALIZED)
    override val lifecycle: StateFlow<ModuleLifecycle> = _lifecycle.asStateFlow()

    private var _isEnabled: Boolean = true
    override var isEnabled: Boolean
        get() = _isEnabled
        set(value) { _isEnabled = value }

    // ── 生命周期默认空实现（子类按需 override）──
    override suspend fun onAwake() {}
    override suspend fun onStart() {}
    override suspend fun onDestroy() {}

    override suspend fun onConfigChanged(newConfig: ModuleConfigBase) {
        // 默认不响应；子类需持久化新配置时 override
    }

    /**
     * 默认空实现。声明式工具贡献走 ToolContributor（由注册器读取）；
     * 仍需主动调用 registry.register(...) 的迁移期模块可 override 此方法。
     */


    /** 默认可用；子类按 Key/权限/依赖实测 override。 */
    override suspend fun checkAvailability(): Boolean = true

    override fun markDisabled() {
        _isEnabled = false
        _lifecycle.value = ModuleLifecycle.DISABLED
    }
}
