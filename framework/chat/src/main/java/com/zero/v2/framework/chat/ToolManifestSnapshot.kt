package com.zero.v2.framework.chat

import com.zero.v2.core.common.FunctionDefinition
import com.zero.v2.core.common.PromptPrefix
import org.json.JSONArray
import org.json.JSONObject
import java.util.Properties

/** Pure persisted manifest policy: timestamps never enter the provider's tool definitions. */
internal object ToolManifestSnapshot {
    const val RECENT_MS = 7L * 86_400_000
    const val BYTE_LIMIT = 48_000
    const val MAX_TOOLS = 24
    // Explicit incompatible contract upgrades only; never derived from per-turn catalog changes.
    const val CONTRACT_VERSION = "4"

    fun checkpoint(epoch: Long, tools: List<FunctionDefinition>): Properties = Properties().apply {
        setProperty("epoch", epoch.toString())
        setProperty("contractVersion", CONTRACT_VERSION)
        setProperty("tools", encode(tools))
    }

    fun restore(state: Properties, epoch: Long): List<FunctionDefinition>? {
        if (state.getProperty("epoch") != epoch.toString() || state.getProperty("contractVersion") != CONTRACT_VERSION) return null
        return runCatching { decode(state.getProperty("tools")) }.getOrNull()
    }

    fun choose(catalog: List<FunctionDefinition>, recent: Map<String, Long>, pinned: Set<String>, now: Long): List<FunctionDefinition> {
        val core = CoreToolCatalog.select(catalog)
        val byName = catalog.associateBy { it.name }
        val extras = pinned.sorted().take(4) + recent.entries.filter { now - it.value in 0..RECENT_MS }
            .sortedWith(compareByDescending<Map.Entry<String, Long>> { it.value }.thenBy { it.key }).map { it.key }.take(4)
        val result = core.take(MAX_TOOLS).toMutableList()
        for (name in extras.distinct()) {
            if (result.size >= MAX_TOOLS) break
            val tool = byName[name] ?: continue
            if (result.any { it.name == name }) continue
            if (PromptPrefix.toolManifest(result + tool).toByteArray(Charsets.UTF_8).size <= BYTE_LIMIT) result += tool
        }
        return decode(encode(result.sortedBy { it.name })) // same canonical maps before and after restart
    }

    fun encode(tools: List<FunctionDefinition>): String = JSONArray().apply {
        tools.forEach { put(JSONObject().put("name", it.name).put("description", it.description)
            .put("parameters", JSONObject(it.parameters))) }
    }.toString()

    fun decode(text: String): List<FunctionDefinition> {
        require(text.length <= 200_000)
        val rows = JSONArray(text)
        require(rows.length() <= MAX_TOOLS)
        return (0 until rows.length()).map { index ->
            val row = rows.getJSONObject(index)
            FunctionDefinition(row.getString("name"), row.getString("description"), objectMap(row.getJSONObject("parameters")))
        }
    }

    private fun objectMap(value: JSONObject): Map<String, Any> = value.keys().asSequence().toList().sorted()
        .associateWith { convert(value.get(it)) }
    private fun convert(value: Any): Any = when (value) {
        is JSONObject -> objectMap(value)
        is JSONArray -> (0 until value.length()).map { convert(value.get(it)) }
        else -> value
    }
}
