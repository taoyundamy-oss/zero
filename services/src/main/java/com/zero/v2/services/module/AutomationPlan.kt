package com.zero.v2.services.module

import org.json.JSONArray
import org.json.JSONObject

/**
 * 执行计划模型 — Zero v3.5（Step 3.5 Plan-Execute 引擎）。
 *
 * LLM 将自然语言任务分解为 PlanStep 列表；步骤经 dependsOn 声明依赖，
 * 引擎按"波次"执行（同波无依赖步骤并发，对标 FunctionRegistryModule.executeBatch）。
 */
data class ExecutionPlan(
    val goal: String,
    val steps: List<PlanStep>
) {
    fun toJson(): String {
        val arr = JSONArray()
        steps.forEach { s ->
            arr.put(JSONObject().apply {
                put("index", s.index)
                put("description", s.description)
                put("tool", s.tool ?: JSONObject.NULL)
                put("arguments", JSONObject(s.arguments))
                put("dependsOn", JSONArray(s.dependsOn))
            })
        }
        return JSONObject().put("goal", goal).put("steps", arr).toString()
    }

    companion object {
        fun fromJson(json: String): ExecutionPlan? {
            return try {
                val obj = JSONObject(json)
                val arr = obj.optJSONArray("steps") ?: return null
                val steps = (0 until arr.length()).map { i ->
                    val s = arr.getJSONObject(i)
                    PlanStep(
                        index = if (s.has("index")) identifier(s.get("index")) else i + 1,
                        description = s.optString("description", ""),
                        tool = if (s.isNull("tool")) null else s.optString("tool").ifBlank { null },
                        arguments = s.optJSONObject("arguments")?.let { a ->
                            a.keys().asSequence().associateWith { k -> a.get(k) }
                        } ?: emptyMap(),
                        dependsOn = if (s.has("dependsOn")) s.getJSONArray("dependsOn").let { d ->
                            (0 until d.length()).map { identifier(d.get(it)) }
                        } else emptyList()
                    )
                }
                if (steps.isEmpty()) null else ExecutionPlan(obj.optString("goal", ""), steps)
                    .takeIf { it.validationError() == null }
            } catch (_: Exception) {
                null
            }
        }

        private fun identifier(value: Any): Int {
            require(value is Number) { "Step identifiers must be integers" }
            return requireNotNull(value.toString().toIntOrNull()) { "Invalid step identifier" }
        }
    }
}

/**
 * 计划步骤。
 * @param tool FC 函数名；null = 纯推理/总结步骤（由 LLM 基于依赖结果生成）
 * @param dependsOn 依赖步骤的 1-based 序号列表
 */
data class PlanStep(
    val index: Int,
    val description: String,
    val tool: String?,
    val arguments: Map<String, Any?> = emptyMap(),
    val dependsOn: List<Int> = emptyList()
)

/**
 * 规则降级解析器（DESIGN §7.6 — 不依赖 LLM 输出的唯一性保证系统正确性）。
 *
 * LLM 计划生成失败时的兜底：关键词规则直接产出可执行计划。
 */
object RuleBasedTaskParser {

    fun parse(description: String): ExecutionPlan {
        val desc = description.trim()
        val wantsPush = desc.contains(Regex("推送|通知"))
        val isSearch = desc.contains(Regex("搜索|查一下|查查|新闻|天气|资讯"))
        val query = cleanQuery(desc)

        // 搜索 + 推送: 搜索 → 整合 → 通知栏推送（content 用 {N} 引用整合结果）
        if (wantsPush && isSearch) {
            return ExecutionPlan(
                goal = desc,
                steps = listOf(
                    PlanStep(1, "搜索: $query", "web_search", mapOf("query" to query)),
                    PlanStep(2, "将搜索结果整合为一段简短的推送文案", null, dependsOn = listOf(1)),
                    PlanStep(3, "推送通知", "push_notification",
                        mapOf("title" to desc.take(12), "content" to "{2}"), dependsOn = listOf(2))
                )
            )
        }

        // 搜索类任务: 搜索 → 总结
        if (isSearch) {
            return ExecutionPlan(
                goal = desc,
                steps = listOf(
                    PlanStep(1, "搜索: $query", "web_search", mapOf("query" to query)),
                    PlanStep(2, "总结搜索结果", null, dependsOn = listOf(1))
                )
            )
        }

        // 提醒类任务: 从描述提取时间，默认 30 分钟
        if (desc.contains(Regex("提醒|备忘"))) {
            val time = extractRelativeTime(desc) ?: "+30m"
            return ExecutionPlan(
                goal = desc,
                steps = listOf(
                    PlanStep(1, "设置提醒", "set_reminder",
                        mapOf("time" to time, "remark" to desc))
                )
            )
        }

        // 兜底: 单步推理
        return ExecutionPlan(
            goal = desc,
            steps = listOf(PlanStep(1, "处理任务: $desc", null))
        )
    }

    /** 从描述中提取 "N秒/分钟/小时" 相对时间；无则 null */
    private fun extractRelativeTime(desc: String): String? {
        val m = Regex("""(\d+)\s*(秒|分钟|小时)""").find(desc) ?: return null
        val unit = when (m.groupValues[2]) {
            "秒" -> "s"; "分钟" -> "m"; else -> "h"
        }
        return "+${m.groupValues[1]}$unit"
    }

    /**
     * 搜索关键词清洗: 剥离时间表达与动作词，只留检索主体。
     * "每天中午12：25搜索明日天气并推送通知" → "明日天气"
     */
    private fun cleanQuery(desc: String): String {
        var q = desc
            .replace(Regex("""每\s*(\d+\s*)?(秒|分钟|小时|天|日|周|年)|每[天日]"""), " ")
            .replace(Regex("""\d{1,2}[:：]\d{2}|\d{1,2}\s*点"""), " ")
            .replace(Regex("""早上?|中午|晚[上]?|凌晨"""), " ")
            .replace(Regex("""搜索|查询|查一下|查查|帮我|自动"""), " ")
            .replace(Regex("""并?(推送|发送)(提醒)?(通知|消息)?.*$"""), " ")
        q = q.replace(Regex("""[\s，,、。！!]+"""), " ").trim()
        return q.ifBlank { desc }
    }
}
