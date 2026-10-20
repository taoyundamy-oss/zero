package com.zero.v2.core.moduleapi

import com.zero.v2.core.common.FunctionDefinition

/** 函数发现与 prompt 组装之间的窄 seam；不执行工具，也不改变 ToolDispatcher。 */
interface ToolContextSelector {
    fun select(request: ToolContextRequest = ToolContextRequest()): List<FunctionDefinition>
}

/**
 * P2-6b / C2 的选择请求。
 * - 默认 `includeAllOperational=true` 保持全量语义（迁移兼容/灰度回退）。
 * - C2 有界模式（includeAllOperational=false）：意图匹配取 top [maxTools]（确定性排序）；
 *   无匹配返回基础集（不全量回退，落实"无意图匹配不全量塞工具"）。
 * - `baseToolNames`：固定小目录（几乎总有用）；`maxTools`：top-K 上限（首期 6-10，实测调整）。
 */
data class ToolContextRequest(
    val intent: String? = null,
    val explicitFunctionNames: Set<String> = emptySet(),
    val includeAllOperational: Boolean = true,
    val maxTools: Int = 8,
    val baseToolNames: Set<String> = emptySet(),
)

