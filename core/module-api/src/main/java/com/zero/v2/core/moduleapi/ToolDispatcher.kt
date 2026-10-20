package com.zero.v2.core.moduleapi

import com.zero.v2.core.common.FunctionResult
import com.zero.v2.core.common.ToolCall

/**
 * 工具执行窄 seam：隐藏注册表、权限兼容和 DAG 调度实现。
 * 当前产品裁决为默认放行；本 Interface 不引入审批状态。
 */
interface ToolDispatcher {
    suspend fun execute(name: String, arguments: Map<String, Any?>): FunctionResult
    suspend fun execute(toolCall: ToolCall): FunctionResult
    suspend fun executeBatch(toolCalls: List<ToolCall>): List<FunctionResult>
}
