package com.zero.v2.app.ui.module

import androidx.lifecycle.ViewModel
import com.zero.v2.core.moduleapi.IZeroModule
import com.zero.v2.core.moduleapi.runtimeState
import com.zero.v2.core.modulemanager.ModuleLifecycleManager
import dagger.hilt.android.lifecycle.HiltViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import javax.inject.Inject

data class ModuleCenterState(
    val modules: List<IZeroModule> = emptyList(),
    val runtimeStates: Map<String, com.zero.v2.core.moduleapi.ModuleRuntimeState> = emptyMap(),
    val version: Int = 0
)

@HiltViewModel
class ModuleCenterViewModel @Inject constructor(
    modules: Set<@JvmSuppressWildcards IZeroModule>,
    private val lifecycleManager: ModuleLifecycleManager
) : ViewModel() {

    private val moduleMap = modules.associateBy { it.metadata.id }

    private val _state = MutableStateFlow(ModuleCenterState(
        modules = modules.sortedBy { it.metadata.launchOrder }
    ))
    val state: StateFlow<ModuleCenterState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            lifecycleManager.runtimeStates.collect { statuses ->
                _state.value = _state.value.copy(
                    runtimeStates = statuses,
                    version = _state.value.version + 1
                )
            }
        }
    }

    /** 切换模块启用/禁用状态 */
    fun toggleModule(moduleId: String) {
        val module = moduleMap[moduleId] ?: return
        if (lifecycleManager.isOperational(moduleId)) {
            lifecycleManager.setUserEnabled(moduleId, false)
            publish()
        } else if (module.lifecycle.value == com.zero.v2.core.moduleapi.ModuleLifecycle.DISABLED) {
            viewModelScope.launch {
                lifecycleManager.reEnable(moduleId)
                publish()
            }
        } else {
            lifecycleManager.setUserEnabled(moduleId, true)
            publish()
        }
    }

    private fun publish() {
        // version 递增强制 StateFlow 发射，解决结构相等导致的 UI 不刷新
        _state.value = _state.value.copy(
            modules = _state.value.modules.toList(),
            version = _state.value.version + 1
        )
    }
}
