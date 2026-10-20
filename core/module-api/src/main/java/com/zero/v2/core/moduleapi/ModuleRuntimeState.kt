package com.zero.v2.core.moduleapi

/** 模块运行态快照：统一表达用户开关与生命周期，而不是分别解释两个字段。 */
data class ModuleRuntimeState(
    val userEnabled: Boolean,
    val lifecycle: ModuleLifecycle
) {
    /** 可被业务调用的有效状态。尚未初始化仍视为待启动，而非禁用。 */
    val operational: Boolean
        get() = userEnabled && lifecycle != ModuleLifecycle.DISABLED &&
            lifecycle != ModuleLifecycle.DESTROYED
}

/** 在读取模块列表、UI 状态及执行前检查时使用同一套状态判定。 */
fun IZeroModule.runtimeState(): ModuleRuntimeState =
    ModuleRuntimeState(userEnabled = isEnabled, lifecycle = lifecycle.value)
