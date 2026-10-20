package com.zero.v2.services.mcp

import java.util.UUID

private const val CONNECTION_ID_LENGTH = 16

/** One credential-reference rule for UI and conversational connection creation. */
object McpCredentialReference {
    fun newConnectionId(): String = "svr_" + UUID.randomUUID().toString().replace("-", "").take(CONNECTION_ID_LENGTH)

    /** Blank means no credential mutation; shared legacy references fork only on an explicit key change. */
    fun forWrite(
        server: McpServerConfig,
        hasNewKey: Boolean,
        connections: List<McpServerConfig> = emptyList(),
    ): String {
        val otherReferences = connections.filter { it.id != server.id }.map { it.apiKeyRef }.toSet()
        return when {
            !hasNewKey -> server.apiKeyRef
            server.apiKeyRef.isNotBlank() && server.apiKeyRef !in otherReferences -> server.apiKeyRef
            else -> {
                val candidate = "mcp_server:${server.id}"
                if (candidate in otherReferences) "$candidate:${UUID.randomUUID()}" else candidate
            }
        }
    }
}
