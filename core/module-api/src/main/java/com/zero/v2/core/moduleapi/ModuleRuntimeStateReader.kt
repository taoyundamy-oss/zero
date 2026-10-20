package com.zero.v2.core.moduleapi

import com.zero.v2.core.moduleapi.ModuleRuntimeState
import kotlinx.coroutines.flow.StateFlow

/** 只读模块运行态 seam，供工具上下文筛选等观察者使用。 */
interface ModuleRuntimeStateReader {
    val runtimeStates: StateFlow<Map<String, ModuleRuntimeState>>
}
