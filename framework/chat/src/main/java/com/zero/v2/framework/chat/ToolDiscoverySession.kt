package com.zero.v2.framework.chat

import com.zero.v2.core.common.FunctionDefinition
import com.zero.v2.core.moduleapi.ToolContextRequest
import com.zero.v2.core.moduleapi.ToolContextSelector
import com.zero.v2.core.common.ToolCall
import org.json.JSONArray
import org.json.JSONObject

/** Fixed provider schemas; discovery returns real schemas as append-only tool results. */
internal class ToolDiscoverySession(
    private val selector: ToolContextSelector,
    @Suppress("UNUSED_PARAMETER") initial: List<FunctionDefinition>,
    maxTools: Int,
    frozen: List<FunctionDefinition>? = null,
) {
    private val pageSize = (maxTools - 1).coerceIn(1, 19)
    val tools: List<FunctionDefinition> = listOf(definition, invokeDefinition) +
        (frozen ?: CoreToolCatalog.select(selector.select(ToolContextRequest())))

    fun discover(query: String, offset: Int, category: String = ""): String {
        val catalog = selector.select(ToolContextRequest()).filterNot { it.name == NAME }.sortedBy { it.name }
        val eligible = catalog.filter { category.isBlank() || categoryOf(it.name) == category }
        // LLMs naturally send keyword lists, not literal phrases. Rank matches without expanding provider schemas.
        val terms = query.trim().split(Regex("[\\s,，;；|]+")).filter { it.isNotBlank() }.distinct()
        val matches = if (terms.isEmpty()) eligible else eligible.map { tool ->
            tool to terms.count { term ->
                tool.name.contains(term, ignoreCase = true) || tool.description.contains(term, ignoreCase = true)
            }
        }.filter { it.second > 0 }
            .sortedWith(compareByDescending<Pair<FunctionDefinition, Int>> { (tool, _) ->
                terms.any { it.equals(tool.name, ignoreCase = true) }
            }.thenByDescending { it.second }.thenBy { it.first.name })
            .map { it.first }
        val start = offset.coerceIn(0, matches.size)
        val page = matches.drop(start).take(pageSize)
        val next = (start + page.size).takeIf { it < matches.size }
        return JSONObject().put("nextOffset", next ?: JSONObject.NULL).put("total", matches.size)
            .put("categories", JSONObject(catalog.groupingBy { categoryOf(it.name) }.eachCount()))
            .put("instruction", "Call invoke_tool with name and arguments matching the returned schema. Empty query browses all tools.")
            .put("tools", JSONArray().apply {
                page.forEach { tool ->
                    put(JSONObject().put("name", tool.name).put("description", tool.description).put("category", categoryOf(tool.name))
                        .put("parameters", JSONObject(tool.parameters)))
                }
            }).toString()
    }

    /** Resolve against the current operational catalog; dispatch still uses the normal guarded registry. */
    fun resolve(call: ToolCall): ToolCall {
        if (call.name != INVOKE_NAME) {
            val advertised = tools.firstOrNull { it.name == call.name && it.name !in setOf(NAME, INVOKE_NAME) }
            if (advertised != null) {
                val current = selector.select(ToolContextRequest()).firstOrNull { it.name == call.name }
                require(current != null && com.zero.v2.core.common.PromptPrefix.toolManifest(listOf(current)) ==
                    com.zero.v2.core.common.PromptPrefix.toolManifest(listOf(advertised))) {
                    "TOOL_SCHEMA_CHANGED: 工具已停用或参数变化，请discover_tools后用invoke_tool调用当前版本。"
                }
            }
            return call
        }
        val payload = JSONObject(call.arguments)
        val name = payload.getString("name")
        require(name != INVOKE_NAME &&
            (name == NAME || name == ToolResultAccess.NAME || selector.select(ToolContextRequest()).any { it.name == name })) {
            "TOOL_UNAVAILABLE: $name 当前不在可用目录。请直接调用 discover_tools 查询，不要重复同一失败调用。"
        }
        return ToolCall(call.id, name, payload.getJSONObject("arguments").toString())
    }

    companion object {
        fun categoryOf(name: String): String = when {
            name.startsWith("memory_") -> "memory"
            name.startsWith("kb_") -> "knowledge"
            name.startsWith("skill_") -> "skills"
            name.startsWith("music_") -> "music"
            name.startsWith("calendar_") || name in setOf("set_alarm", "set_timer") -> "schedule"
            name.startsWith("dyn_") || name.startsWith("mcp_") -> "extensions"
            else -> "system"
        }
        const val NAME = "discover_tools"
        const val INVOKE_NAME = "invoke_tool"
        val invokeDefinition = FunctionDefinition(
            INVOKE_NAME, "执行已通过 discover_tools 找到的工具。name填真实工具名，arguments严格遵守发现结果中的参数schema。",
            mapOf("type" to "object", "properties" to mapOf(
                "name" to mapOf("type" to "string"),
                "arguments" to mapOf("type" to "object", "additionalProperties" to true),
            ), "required" to listOf("name", "arguments")),
        )
        val definition = FunctionDefinition(
            NAME,
            "查找可用工具并返回名称、分类、说明和完整参数schema，再用 invoke_tool 执行。空query从offset=0分类浏览；按nextOffset继续。",
            mapOf(
                "type" to "object",
                "properties" to mapOf(
                    "query" to mapOf("type" to "string", "description" to "名称或描述关键词；空字符串浏览全部"),
                    "offset" to mapOf("type" to "integer", "minimum" to 0),
                    "category" to mapOf("type" to "string", "description" to "可选分类：memory/knowledge/skills/music/schedule/extensions/system；空为全部"),
                ),
            ),
        )
    }
}
