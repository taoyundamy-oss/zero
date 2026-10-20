package com.zero.v2.core.moduleapi

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** 运行态快照存储，确保所有状态变更通过同一 StateFlow 广播。 */
class ModuleRuntimeStateStore(initial: Map<String, ModuleRuntimeState>) {
    private val _states = MutableStateFlow(initial.toMap())
    val states: StateFlow<Map<String, ModuleRuntimeState>> = _states.asStateFlow()

    fun update(id: String, state: ModuleRuntimeState) {
        _states.update { it + (id to state) }
    }

    fun setUserEnabled(id: String, enabled: Boolean): Boolean {
        var changed = false
        _states.update { current ->
            val previous = current[id] ?: return@update current
            changed = true
            current + (id to previous.copy(userEnabled = enabled))
        }
        return changed
    }
}
