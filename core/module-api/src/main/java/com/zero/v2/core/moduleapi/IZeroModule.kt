package com.zero.v2.core.moduleapi

import kotlinx.coroutines.flow.StateFlow

/**
 * Zero 模块基础接口 — 对标 Alife 的 InteractiveModule<T>。
 *
 * 每个模块拥有完整的标识、生命周期、配置注入、FC 工具注册和
 * Android 权限声明能力。所有模块通过 Hilt @IntoSet 自动收集，
 * ModuleLifecycleManager 统一管理。
 *
 * 生命周期：
 *   onAwake() → onStart() → [onTick() for ITimedModule] → onDestroy()
 *
 * 崩溃隔离：
 *   Core 模块崩溃 → 记录日志 + 通知用户（不自动禁用）
 *   非 Core 模块崩溃 → 自动标记 DISABLED + Poke 通知
 */
interface IZeroModule {

    // ── 标识 ──
    /** 模块元数据（ID、名称、分类、启动顺序等） */
    val metadata: ModuleMetadata

    // ── 状态 ──
    /** 当前生命周期状态 */
    val lifecycle: StateFlow<ModuleLifecycle>

    /** 模块是否启用（用户可在设置中切换） */
    var isEnabled: Boolean

    // ── 生命周期 ──
    /**
     * 模块唤醒阶段。
     * 在此阶段注册 FC 函数、读取配置、初始化内部状态。
     * 对标 Alife AwakeAsync(AwakeContext)。
     */
    suspend fun onAwake()

    /**
     * 模块启动阶段。
     * 在此阶段订阅事件总线、启动定时器、激活后台任务。
     * 对标 Alife StartAsync(Kernel, ChatActivity)。
     */
    suspend fun onStart()

    /**
     * 模块销毁阶段。
     * 在此阶段取消订阅、停止定时器、释放资源。
     * 对标 Alife DestroyAsync()。
     */
    suspend fun onDestroy()

    // ── 配置 ──
    /**
     * 模块配置。
     * 由 ModuleLifecycleManager 在 onAwake() 前从持久化存储加载。
     */
    val config: ModuleConfigBase

    /**
     * 配置变更回调。
     * 用户在设置中修改配置后，ModuleLifecycleManager 调用此方法。
     */
    suspend fun onConfigChanged(newConfig: ModuleConfigBase)

    // ── 可用性 ──
    /**
     * 检查模块当前是否可用。
     *
     * 验证条件：
     * - API Key 是否已配置
     * - 网络是否可达
     * - 所需权限是否已授予
     * - 依赖的其他模块是否正常运行
     */
    suspend fun checkAvailability(): Boolean

    // ── Android 特定 ──
    /**
     * 模块需要的 Android 权限列表。
     * 用于权限引导 UI 和运行时检查。
     */
    val requiredPermissions: List<String>

    // ── 崩溃隔离 ──
    /**
     * 由 ModuleLifecycleManager 调用，标记模块为禁用状态。
     * 非 Core 模块在崩溃后自动调用。
     */
    fun markDisabled()
}
