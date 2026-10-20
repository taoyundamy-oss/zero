package com.zero.v2.core.modulemanager

import com.zero.v2.core.moduleapi.FunctionCatalog
import com.zero.v2.core.moduleapi.ModuleRuntimeStateReader
import com.zero.v2.core.moduleapi.ToolContextSelector
import com.zero.v2.core.moduleapi.ToolContextRequest

import com.zero.v2.core.common.FunctionDefinition
import javax.inject.Provider
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DefaultToolContextSelector @Inject constructor(
    private val catalog: FunctionCatalog,
    private val runtimeStateReaderProvider: Provider<ModuleRuntimeStateReader>,
) : ToolContextSelector {
    override fun select(request: ToolContextRequest): List<FunctionDefinition> {
        val definitions = catalog.getAllDefinitions()
        val disabledNames = runtimeStateReaderProvider.get().runtimeStates.value
            .filterValues { !it.operational }
            .keys
            .flatMap { moduleId -> catalog.getEntriesByModule(moduleId) }
            .map { it.definition.name }
            .toSet()
        return ToolContextSelectionPolicy.select(
            definitions = definitions,
            disabledNames = disabledNames,
            request = request,
        )
    }
}

/** 纯策略，便于 contract test；模块状态只在适配层投影为 disabledNames。 */
object ToolContextSelectionPolicy {
    fun select(
        definitions: List<FunctionDefinition>,
        disabledNames: Set<String> = emptySet(),
        request: ToolContextRequest = ToolContextRequest(),
    ): List<FunctionDefinition> {
        val operational = definitions.filterNot { it.name in disabledNames }
        if (request.explicitFunctionNames.isNotEmpty()) {
            return operational.filter { it.name in request.explicitFunctionNames }
        }
        val intent = request.intent?.trim().orEmpty()
        if (intent.isNotEmpty()) {
            val terms = intent.split(Regex("[\\s,，。！？；;、]+"))
                .filter { it.isNotEmpty() }
            // C2：按相关性评分排序（name 命中 > description 命中 > bigram），确定性 tie-break 按 catalog 序+name
            val scored = operational.mapIndexed { idx, def -> idx to scoreMatch(def, terms) }
                .filter { it.second > 0 }
                .sortedWith(
                    compareByDescending<Pair<Int, Int>> { it.second }
                        .thenBy { it.first }
                        .thenBy { operational[it.first].name }
                )
                .map { operational[it.first] }
            if (scored.isNotEmpty()) return scored.take(request.maxTools.coerceAtLeast(1))
            // 无匹配：有界模式不全量回退——返回基础集（下面处理）
            if (request.includeAllOperational) return operational
            // fall-through to base set
        }
        // 无意图（或有界无匹配）+ 全量模式 → 全量；有界模式 → 基础集（不全量塞）
        return if (request.includeAllOperational) {
            operational
        } else {
            operational.filter { it.name in request.baseToolNames }.take(request.maxTools.coerceAtLeast(1))
        }
    }

    /** 相关性评分：name 直接命中=10，description 命中=4，bigram 命中=1（累加）。 */
    private fun scoreMatch(def: FunctionDefinition, terms: List<String>): Int {
        var score = 0
        for (term in terms) {
            if (def.name.contains(term, ignoreCase = true)) score += 10
            if (def.description.contains(term, ignoreCase = true)) score += 4
            if (term.length >= 2) {
                term.windowed(2).forEach { pair ->
                    if (def.name.contains(pair, ignoreCase = true)) score += 1
                    if (def.description.contains(pair, ignoreCase = true)) score += 1
                }
            }
        }
        return score
    }
}
