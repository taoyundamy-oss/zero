package com.zero.v2.app.ui.settings

import com.zero.v2.core.modulemanager.ModuleLifecycleManager
import com.zero.v2.services.mcp.McpClientModule
import com.zero.v2.services.mcp.McpRefreshReport
import kotlinx.coroutines.flow.StateFlow

internal interface McpConnectionActions {
    val discoveredCount: StateFlow<Int>
    suspend fun refresh(): McpRefreshReport
}

internal class ManagedMcpConnectionActions(private val module: McpClientModule,
    private val lifecycle: ModuleLifecycleManager) : McpConnectionActions {
    override val discoveredCount get() = module.discoveredCount
    override suspend fun refresh() =
        checkNotNull(lifecycle.safeCall(module, "ui_mcp_refresh") { module.refreshAllTools() })
}
