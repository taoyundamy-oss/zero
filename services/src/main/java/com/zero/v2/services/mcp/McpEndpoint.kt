package com.zero.v2.services.mcp

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** Provider URL handling is shared by UI/LLM saves and the MCP transport; credentials stay in their owner store. */
object McpEndpoint {
    fun forStorage(server: McpServerConfig, hasNewKey: Boolean): McpServerConfig {
        val url = server.url.toHttpUrlOrNull() ?: return server
        return if (url.host == "mcp.amap.com" && hasNewKey)
            server.copy(url = url.newBuilder().removeAllQueryParameters("key").build().toString()) else server
    }

    fun forTransport(server: McpServerConfig, key: String?): String {
        val url = server.url.toHttpUrlOrNull() ?: return server.url
        return if (url.host == "mcp.amap.com" && !key.isNullOrBlank())
            url.newBuilder().setQueryParameter("key", key).build().toString() else server.url
    }

    fun display(url: String): String = url.toHttpUrlOrNull()?.newBuilder()
        ?.username("")?.password("")?.query(null)?.fragment(null)?.build()?.toString() ?: "地址格式待检查"

    /** Query/userinfo/fragment inputs are conservatively treated as sensitive and excluded from SavedState. */
    fun canSaveDraft(url: String): Boolean {
        val parsed = url.toHttpUrlOrNull() ?: return url.isBlank()
        return parsed.query == null && parsed.fragment == null && parsed.username.isEmpty() && parsed.password.isEmpty()
    }
}
