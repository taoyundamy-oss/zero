package com.zero.v2.core.modulemanager

import com.zero.v2.core.bus.ChatBus
import com.zero.v2.core.common.FunctionResult
import com.zero.v2.core.moduleapi.ToolExecutionGuard
import com.zero.v2.core.trace.AgentTraceManager
import javax.inject.Provider

/** Tests of schema/DAG behavior only. ToolOwnerExecutionTest uses the real lifecycle guard. */
internal fun testRegistry(bus: ChatBus, trace: AgentTraceManager) = FunctionRegistryModule(bus, trace,
    Provider { object : ToolExecutionGuard {
        override suspend fun executeForModule(moduleId: String, operation: String,
            block: suspend () -> FunctionResult): FunctionResult = block()
    } })
