package com.zero.v2.core.moduleapi

/**
 * 模块生命周期状态。
 *
 * 对标 Alife 的 InteractiveModule 生命周期阶段。
 */
enum class ModuleLifecycle {
    /** 未初始化 */
    UNINITIALIZED,
    /** onAwake() 已执行 — 函数已注册，配置已加载 */
    AWAKE,
    /** onStart() 已执行 — 事件已订阅，定时器已启动 */
    STARTED,
    /** onDestroy() 已执行 — 资源已释放 */
    DESTROYED,
    /** 异常导致自动禁用 */
    DISABLED
}
