package com.zero.v2.core.modulemanager

import com.zero.v2.core.moduleapi.ToolContributor
import com.zero.v2.core.moduleapi.FunctionEntry

import com.zero.v2.core.moduleapi.IZeroModule

/** 启动时校验模块、FC 与 trace source 的注册边界。 */
object RegistrationValidator {
    private val idPattern = Regex("[a-z][a-z0-9_]{0,39}")
    // OpenAI tool names允许字母、数字、下划线和短横线；首字符仍要求字母。
    private val functionPattern = Regex("[A-Za-z][A-Za-z0-9_-]{0,63}")
    private val reservedTraceSources = setOf("system", "function_registry", "mcp_client")

    fun validateModules(modules: Collection<IZeroModule>) {
        val errors = modules.groupBy { it.metadata.id }.flatMap { (id, sameId) ->
            buildList {
                if (!idPattern.matches(id)) add("非法模块 ID: $id")
                if (sameId.size > 1) add("重复模块 ID: $id")
            }
        }
        require(errors.isEmpty()) { errors.joinToString("；") }
    }

    fun validateFunctions(entries: Collection<FunctionEntry>, moduleIds: Set<String>) {
        val errors = buildList {
            entries.groupBy { it.definition.name }.filterValues { it.size > 1 }
                .keys.forEach { add("重复 FC name: $it") }
            entries.forEach { entry ->
                val name = entry.definition.name
                if (!functionPattern.matches(name)) add("非法 FC name: $name")
                if (entry.sourceModuleId !in moduleIds && entry.sourceModuleId !in reservedTraceSources) {
                    add("FC 来源模块未注册: $name -> ${entry.sourceModuleId}")
                }
            }
        }
        require(errors.isEmpty()) { errors.joinToString("；") }
    }

    fun validateTraceSource(source: String, moduleIds: Set<String>): Boolean =
        source in moduleIds || source in reservedTraceSources || source.startsWith("cfg_") ||
            source.startsWith("crash_") || source.startsWith("trace_")

    /**
     * F1：校验声明式工具贡献（ToolContributor）。
     * 拒绝：无 schema（parameters 空）且未标记 noArgs 的声明；同模块内重复 FC name；
     * 非法 FC name。permission 为非空类型，结构上必填（落实"无权限声明被拒绝"）。
     */
    fun validateToolContributors(modules: Collection<IZeroModule>) {
        val errors = buildList {
            modules.filterIsInstance<ToolContributor>().forEach { module ->
                val contributor = module as IZeroModule
                val seen = mutableSetOf<String>()
                for (c in module.contributions()) {
                    val name = c.definition.name
                    if (!functionPattern.matches(name)) add("非法 FC name: $name")
                    if (!seen.add(name)) add("同模块重复 FC name: $name")
                    if (!c.noArgs && c.definition.parameters.isEmpty()) {
                        add("缺少参数 schema: $name（无参工具需显式 noArgs=true）")
                    }
                }
            }
        }
        require(errors.isEmpty()) { errors.joinToString("；") }
    }
}
