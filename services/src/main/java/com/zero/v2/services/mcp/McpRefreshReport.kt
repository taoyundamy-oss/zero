package com.zero.v2.services.mcp

/** Connection IDs and counts only; provider URLs, keys and exception bodies never enter UI reports. */
data class McpRefreshReport(
    val toolCount: Int,
    val failedIds: List<String> = emptyList(),
    val emptyIds: List<String> = emptyList(),
    val stale: Boolean = false,
) {
    val successful: Boolean get() = failedIds.isEmpty() && !stale
    fun message(): String = when {
        stale -> "连接已变更，旧发现结果已丢弃，请重新刷新"
        failedIds.isNotEmpty() -> "发现失败 ${failedIds.size} 个连接；当前 $toolCount 个工具，可检查连接后重试"
        emptyIds.isNotEmpty() -> "发现完成：$toolCount 个工具，${emptyIds.size} 个连接未提供工具"
        else -> "发现完成：$toolCount 个工具"
    }
}
