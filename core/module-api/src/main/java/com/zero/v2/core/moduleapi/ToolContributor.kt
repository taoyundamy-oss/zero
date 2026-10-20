package com.zero.v2.core.moduleapi

import com.zero.v2.core.common.ActionPermission
import com.zero.v2.core.common.FunctionDefinition
import com.zero.v2.core.common.FunctionResult

/**
 * 可选能力：声明式贡献 FC 工具。
 *
 * 设计（迭代总纲§3 / 迭代开发设计§2.1）：工具声明由注册器（FunctionRegistryModule）
 * 统一读取并注册，替代各模块在 `registerFunctions()` 内主动调用 `registry.register(...)`
 * 的分散模式。实现本接口的模块只需返回 [contributions]，[ToolRegistrar] 在 onAwake
 * 阶段读取并集中注册。
 *
 * 静态工具统一实现本接口；运行时发现的动态工具通过 [ToolRegistrar] 刷新。
 *
 * 契约位于 module-api；FunctionResult 位于 common。业务模块不依赖注册器实现。
 */
interface ToolContributor {
    /** 返回此模块贡献的工具声明。由注册器在 onAwake 后读取，幂等覆盖同名条目。 */
    fun contributions(): List<ToolContribution>
}

/**
 * 单条工具声明：定义 + 显式权限 + handler。
 *
 * @param definition OpenAI 兼容工具定义（name/description/parameters schema）。
 * @param permission 显式行动权限。**必填**——不提供则无法构造，落实 F1 验收
 *   "无权限声明被拒绝"。默认放行（AUTO）仍需显式声明（Gate 1：取消安全分级但保留声明）。
 * @param noArgs 标记无参工具。true 时允许 `definition.parameters` 为空；false 且 parameters
 *   为空被 [RegistrationValidator.validateToolContributors] 拒绝（"无 schema 被拒绝"）。
 * @param handler 执行体，返回 [FunctionResult]。
 */
data class ToolContribution(
    val definition: FunctionDefinition,
    val permission: ActionPermission,
    val noArgs: Boolean = false,
    val handler: suspend (Map<String, Any?>) -> FunctionResult
)
