package com.zero.v2.core.modulemanager

import com.zero.v2.core.common.FunctionDefinition
import com.zero.v2.core.common.ToolCall

/** 已通过校验的工具调用拓扑波次；每个波次内的调用可并行执行。 */
internal data class FunctionCallDagPlan(val waves: List<List<Int>>)

internal data class FunctionCallDagPlanResult(
    val plan: FunctionCallDagPlan?,
    val error: String? = null
)

/** 根据函数级 dependsOn 构建 DAG，并拒绝缺失依赖、歧义依赖和环。 */
internal fun planFunctionCallDag(
    toolCalls: List<ToolCall>,
    definitions: Map<String, FunctionDefinition>
): FunctionCallDagPlanResult {
    if (toolCalls.isEmpty()) return FunctionCallDagPlanResult(FunctionCallDagPlan(emptyList()))

    val dependencies = Array(toolCalls.size) { linkedSetOf<Int>() }
    toolCalls.forEachIndexed { index, toolCall ->
        val definition = definitions[toolCall.name] ?: return@forEachIndexed
        definition.dependsOn.distinct().forEach { dependencyName ->
            val candidates = toolCalls.indices.filter { it != index && toolCalls[it].name == dependencyName }
            if (candidates.isEmpty()) {
                return FunctionCallDagPlanResult(null, "${toolCall.name}[$index] 缺少依赖函数 $dependencyName")
            }
            val previous = candidates.filter { it < index }
            val dependencyIndex = when {
                previous.isNotEmpty() -> previous.last()
                candidates.size == 1 -> candidates.single()
                else -> return FunctionCallDagPlanResult(null, "${toolCall.name}[$index] 的依赖 $dependencyName 无法唯一解析")
            }
            if (dependencyIndex == index) {
                return FunctionCallDagPlanResult(null, "${toolCall.name}[$index] 不能依赖自身")
            }
            dependencies[index].add(dependencyIndex)
        }
    }

    val remaining = toolCalls.indices.toMutableSet()
    val waves = mutableListOf<List<Int>>()
    while (remaining.isNotEmpty()) {
        val wave = remaining.filter { index -> dependencies[index].none { it in remaining } }
        if (wave.isEmpty()) return FunctionCallDagPlanResult(null, "工具调用依赖图存在环")
        waves += wave
        remaining.removeAll(wave.toSet())
    }
    return FunctionCallDagPlanResult(FunctionCallDagPlan(waves))
}
