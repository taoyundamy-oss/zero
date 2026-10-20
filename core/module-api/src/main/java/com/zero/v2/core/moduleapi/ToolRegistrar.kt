package com.zero.v2.core.moduleapi

import com.zero.v2.core.common.ActionPermission
import com.zero.v2.core.common.FunctionDefinition
import com.zero.v2.core.common.FunctionResult

/** Tool registration contract. Execution and discovery are separate capabilities. */
interface ToolRegistrar {
    fun register(
        definition: FunctionDefinition,
        sourceModuleId: String,
        handler: suspend (Map<String, Any?>) -> FunctionResult,
    ) = register(definition, sourceModuleId, ActionPermission.AUTO, handler)

    fun register(
        definition: FunctionDefinition,
        sourceModuleId: String,
        permission: ActionPermission,
        handler: suspend (Map<String, Any?>) -> FunctionResult,
    )

    fun unregister(name: String)
    fun registerContributions(module: IZeroModule): Int
}
