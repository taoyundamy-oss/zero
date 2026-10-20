package com.zero.v2.core.moduleapi

import com.zero.v2.core.common.FunctionResult

/** Source-owner execution check and crash isolation; no user approval policy. */
interface ToolExecutionGuard {
    suspend fun executeForModule(
        moduleId: String,
        operation: String,
        block: suspend () -> FunctionResult,
    ): FunctionResult
}
