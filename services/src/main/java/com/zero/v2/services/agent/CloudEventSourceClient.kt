package com.zero.v2.services.agent

import com.zero.v2.services.mcp.McpClientModule
import com.zero.v2.services.security.CredentialStore
import javax.inject.Inject

/** Legacy cloud event transport retained for existing configured consumers, not a second tool dispatcher. */
class CloudEventSourceClient @Inject constructor(
    private val mcp: McpClientModule,
    private val credentials: CredentialStore,
) {
    var onAsyncTaskSubmitted: ((String, String) -> Unit)?
        get() = mcp.onAsyncTaskSubmitted
        set(value) { mcp.onAsyncTaskSubmitted = value }

    suspend fun callToolForServer(serverName: String, toolName: String, args: Map<String, Any?>) =
        mcp.callToolForServer(serverName, toolName, args)

    fun credential(): String? = credentials.getOrFallbackString("mcp_server:zero-cloud", null)
}
