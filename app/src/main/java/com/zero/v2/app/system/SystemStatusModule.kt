package com.zero.v2.app.system

import com.zero.v2.core.common.ActionPermission
import com.zero.v2.core.common.FunctionDefinition
import com.zero.v2.core.common.FunctionResult
import com.zero.v2.core.moduleapi.ModuleCategory
import com.zero.v2.core.moduleapi.ModuleConfigBase
import com.zero.v2.core.moduleapi.ModuleDescriptor
import com.zero.v2.core.moduleapi.ModuleMetadata
import com.zero.v2.core.moduleapi.SystemStateRepository
import com.zero.v2.core.moduleapi.ToolContribution
import com.zero.v2.core.moduleapi.ToolContributor
import com.zero.v2.core.trace.AgentTraceManager
import com.zero.v2.core.trace.NodeType
import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 系统状态查询模块（S1）——把 [SystemStateRepository.zeroStatus] 包成 `zero_status` FC
 * 暴露给 LLM。只读、AUTO 权限、不触发付费模型或动作。
 *
 * 声明式工具贡献（F1 [ToolContributor]）：由注册器在 onAwake 后读取注册，
 * 不主动调用 registry.register。LLM 调用 zero_status → handler 解析 sections/
 * sinceRevision/limit → 委托 [SystemStateRepository] → 返回脱敏 JSON 投影。
 */
@Singleton
class SystemStatusModule @Inject constructor(
    private val systemState: SystemStateRepository,
    private val traceManager: AgentTraceManager,
) : ModuleDescriptor(), ToolContributor {

    override val metadata = ModuleMetadata(
        id = "system_status",
        displayName = "系统状态",
        description = "查询 Zero 当前系统状态（只读投影）：模块运行态、模型路由、回合、termux、预算、检索域、语音会话。",
        category = ModuleCategory.SYSTEM,
        launchOrder = 200,
    )

    override val config = object : ModuleConfigBase() {
        override fun toJson() = "{}"
        override fun fromJson(json: String) {}
        override fun defaultConfig() = this
    }

    override val requiredPermissions: List<String> = emptyList()

    override fun contributions(): List<ToolContribution> = listOf(
        ToolContribution(
            definition = FunctionDefinition(
                name = "zero_status",
                description = "查询 Zero 当前系统状态的只读投影。可选 sections：" +
                    "modules（模块运行态）、models（模型路由/可用性）、runtime（回合占用）、" +
                    "termux（supervisor health）、budgets（预算档位 ok/low/exceeded）、" +
                    "retrieval（记忆/知识库可用性）、voice（语音会话）、resources（资源档位）、" +
                    "permissions（权限状态）、brief（≤200 token 摘要）。" +
                    "传 sinceRevision 仅返回 revision 新于该值的变化（轮询增量）。" +
                    "不触发付费模型、安装软件或唤醒重服务；动作执行前仍由原始 owner 实时校验。",
                parameters = mapOf(
                    "type" to "object",
                    "properties" to mapOf(
                        "sections" to mapOf(
                            "type" to "array",
                            "items" to mapOf("type" to "string"),
                            "description" to "请求的 section 集合；省略则默认 [brief]"
                        ),
                        "sinceRevision" to mapOf(
                            "type" to "integer",
                            "description" to "仅返回 revision 严格新于此值的变化；省略返回当前全量"
                        ),
                        "limit" to mapOf(
                            "type" to "integer",
                            "description" to "每页最多条目数（默认 50，上限 100）"
                        ),
                        "cursor" to mapOf("type" to "string", "description" to "上一页 nextCursor；过期时省略重新查询")
                    )
                )
            ),
            permission = ActionPermission.AUTO,
            handler = { args -> handleZeroStatus(args) }
        )
    )

    /** 解析参数 → 委托 repository → 脱敏 JSON。 */
    private suspend fun handleZeroStatus(args: Map<String, Any?>): FunctionResult {
        val sections = (args["sections"] as? List<*>)?.filterIsInstance<String>()?.toSet()
            ?: setOf("brief")
        val sinceRevision = (args["sinceRevision"] as? Number)?.toLong()
        val limit = (args["limit"] as? Number)?.toInt() ?: 50
        return try {
            val result = systemState.zeroStatus(sections, sinceRevision, limit, args["cursor"] as? String)
            traceManager.record("fc_zero_status", NodeType.TOOL_CALL_RESULT, metadata.id,
                "zero_status_queried: sections=${sections.joinToString(",")} rev=${result.revision}")
            FunctionResult.success(renderJson(result))
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (t: Exception) {
            traceManager.record("fc_zero_status", NodeType.ERROR, metadata.id,
                "zero_status_failed: ${t.javaClass.simpleName}")
            FunctionResult.failure("系统状态暂不可读，请稍后重试（status_read_failed）")
        }
    }

    /** ZeroStatusResult → JSON（facts 值为 Map/List/原语，JSONObject.wrap 递归处理）。 */
    private fun renderJson(r: com.zero.v2.core.moduleapi.ZeroStatusResult): String {
        val json = JSONObject()
        json.put("revision", r.revision)
        json.put("capturedAt", r.capturedAt)
        json.put("facts", JSONObject(r.facts))
        json.put("unknown", JSONArray(r.unknown))
        json.put("stale", JSONArray(r.stale))
        json.put("truncated", r.truncated)
        if (r.nextCursor != null) json.put("nextCursor", r.nextCursor)
        return json.toString()
    }
}
