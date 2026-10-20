package com.zero.v2.app.ui.settings

import com.zero.v2.core.data.entity.DynamicModuleEntity
import com.zero.v2.services.mcp.McpClientModule
import com.zero.v2.core.modulemanager.ModuleLifecycleManager
import kotlinx.coroutines.flow.StateFlow

/** UI dependency seam; MCP remains the lifecycle/registration owner. */
internal interface DynamicModuleActions {
    val discoveredCount: StateFlow<Int>
    suspend fun mount(module: DynamicModuleEntity): Boolean
    suspend fun unmount(module: DynamicModuleEntity): Boolean
}

internal class McpDynamicModuleActions(private val mcp: McpClientModule,
    private val lifecycle: ModuleLifecycleManager) : DynamicModuleActions {
    override val discoveredCount get() = mcp.discoveredCount
    override suspend fun mount(module: DynamicModuleEntity) =
        lifecycle.safeCall(mcp, "ui_dynamic_mount") { mcp.mountDynamicModule(module) } ?: false
    override suspend fun unmount(module: DynamicModuleEntity) =
        lifecycle.safeCall(mcp, "ui_dynamic_unmount") { mcp.unmountDynamicModule(module) } ?: false
}
