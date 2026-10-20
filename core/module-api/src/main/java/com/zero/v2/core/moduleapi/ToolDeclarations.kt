package com.zero.v2.core.moduleapi

import com.zero.v2.core.common.ActionPermission
import com.zero.v2.core.common.FunctionDefinition
import com.zero.v2.core.common.FunctionResult

/** Builds declarations locally; collecting a module's tools cannot mutate the live catalog. */
class ToolDeclarations(private val ownerId: String) {
    private val entries = mutableListOf<ToolContribution>()

    fun register(
        definition: FunctionDefinition,
        sourceModuleId: String,
        permission: ActionPermission = ActionPermission.AUTO,
        handler: suspend (Map<String, Any?>) -> FunctionResult,
    ) {
        require(sourceModuleId == ownerId) { "Tool declaration owner mismatch" }
        entries += ToolContribution(definition, permission, definition.parameters.isEmpty(), handler)
    }

    fun register(
        definition: FunctionDefinition,
        sourceModuleId: String,
        handler: suspend (Map<String, Any?>) -> FunctionResult,
    ) = register(definition, sourceModuleId, ActionPermission.AUTO, handler)

    fun snapshot(): List<ToolContribution> = entries.toList()
}

fun toolDeclarations(ownerId: String, block: ToolDeclarations.() -> Unit): List<ToolContribution> =
    ToolDeclarations(ownerId).apply(block).snapshot()

/** Planning needs discovery and execution, but cannot register or remove tools. */
interface ToolRuntime : FunctionCatalog, ToolDispatcher
