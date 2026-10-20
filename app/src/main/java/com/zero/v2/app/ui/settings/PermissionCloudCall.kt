package com.zero.v2.app.ui.settings

import com.zero.v2.services.mcp.McpClientModule
import com.zero.v2.core.modulemanager.ModuleLifecycleManager
import com.zero.v2.core.moduleapi.runtimeState
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import org.json.JSONObject

fun interface PermissionCloudCall {
    suspend fun call(tool: String, args: Map<String, Any?>): JSONObject?
}

@Module
@InstallIn(SingletonComponent::class)
object PermissionCloudBinding {
    @Provides
    fun provideCall(mcp: McpClientModule, manager: ModuleLifecycleManager): PermissionCloudCall = PermissionCloudCall { tool, args ->
        if (!mcp.runtimeState().operational) null
        else manager.safeCall(mcp, "permission:$tool") { mcp.callToolForServer("zero-cloud", tool, args) }
    }
}
