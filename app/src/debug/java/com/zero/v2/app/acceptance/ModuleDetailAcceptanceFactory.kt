package com.zero.v2.app.acceptance

import com.zero.v2.app.ui.module.ModuleDetailViewModel
import com.zero.v2.core.modulemanager.ModuleLifecycleManager
import com.zero.v2.services.security.CredentialStore
import com.zero.v2.core.data.store.ModuleConfigStore
import com.zero.v2.core.moduleapi.FunctionCatalog
import com.zero.v2.core.trace.AgentTraceManager
import javax.inject.Inject

/** Non-exported debug access to the real detail owner; no credential values are exposed. */
class ModuleDetailAcceptanceFactory @Inject constructor(
    private val modules: ModuleLifecycleManager,
    private val credentials: CredentialStore,
    private val configs: ModuleConfigStore,
    private val functions: FunctionCatalog,
    private val traces: AgentTraceManager,
    private val chatBus: com.zero.v2.core.bus.ChatBus,
    private val registrar: com.zero.v2.core.moduleapi.ToolRegistrar,
) {
    fun create() = ModuleDetailViewModel(modules, credentials, configs, functions, traces)

    fun isolated(module: com.zero.v2.core.moduleapi.IZeroModule, store: ModuleConfigStore): ModuleDetailViewModel {
        return isolated(setOf(module), store)
    }

    fun isolated(moduleSet: Set<com.zero.v2.core.moduleapi.IZeroModule>, store: ModuleConfigStore): ModuleDetailViewModel {
        val manager = ModuleLifecycleManager(moduleSet, chatBus, store, traces, functions, registrar)
        return ModuleDetailViewModel(manager, credentials, store, functions, traces)
    }

    fun isolatedCenter(moduleSet: Set<com.zero.v2.core.moduleapi.IZeroModule>, store: ModuleConfigStore)
        : com.zero.v2.app.ui.module.ModuleCenterViewModel {
        val manager = ModuleLifecycleManager(moduleSet, chatBus, store, traces, functions, registrar)
        return com.zero.v2.app.ui.module.ModuleCenterViewModel(moduleSet, manager)
    }
}
