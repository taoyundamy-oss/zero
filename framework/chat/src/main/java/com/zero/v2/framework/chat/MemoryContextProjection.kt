package com.zero.v2.framework.chat

import com.zero.v2.core.common.ChatMessage
import com.zero.v2.core.common.MessageRole
import com.zero.v2.services.retrieval.ContextMemory
import org.json.JSONArray
import org.json.JSONObject

/** Frozen head plus append-only changes/withdrawals. Generation/anchor/budget resets compact it. */
internal class MemoryContextProjection(raw: String? = null) {
    private var epoch = -1L
    private data class Event(val anchor: String?, val entries: List<ContextMemory>, val withdrawn: List<String> = emptyList())
    private val events = mutableListOf<Event>()
    var resetReason: String? = null; private set
    init {
        if (raw != null) runCatching {
            require(raw.length <= 100_000)
            val obj = JSONObject(raw); epoch = obj.getLong("epoch")
            val array = obj.getJSONArray("events"); require(array.length() <= 32)
            for (i in 0 until array.length()) {
                val e = array.getJSONObject(i); val values = e.getJSONArray("entries")
                require(values.length() <= 32)
                val withdrawn = e.optJSONArray("withdrawn") ?: JSONArray()
                require(withdrawn.length() <= 32)
                events += Event(e.optString("anchor").takeIf { it.isNotEmpty() }, (0 until values.length()).map {
                    val m = values.getJSONObject(it)
                    ContextMemory(m.getString("id"), m.getString("version"), m.getString("content"), m.getString("type"), m.getLong("at"))
                }, (0 until withdrawn.length()).map { withdrawn.getString(it) })
            }
            require(knownIds().size <= 64)
        }.onFailure { epoch = -1; events.clear() }
    }

    fun knownIds(): List<String> = events.flatMap { it.entries.map { m -> m.id } + it.withdrawn }.distinct()

    private fun latest(): Map<String, ContextMemory> = linkedMapOf<String, ContextMemory>().apply {
        events.forEach { e -> e.entries.forEach { put(it.id, it) }; e.withdrawn.forEach(::remove) }
    }

    fun project(history: List<ChatMessage>, generation: Long, current: List<ContextMemory>, maxChars: Int): List<ChatMessage> {
        resetReason = null
        val active = current.associateBy { it.id }
        val previous = latest()
        val withdrawn = previous.keys.filter { it !in active }
        val missingAnchor = events.any { e -> e.anchor != null && history.none { it.id == e.anchor } }
        if (generation != epoch || missingAnchor) {
            resetReason = if (missingAnchor) "memory_anchor_missing" else "memory_generation"
            events.clear(); epoch = generation
        }
        val selected = select(current, maxChars)
        if (events.isEmpty()) events += Event(null, selected)
        else {
            val changes = selected.filter { previous[it.id]?.version != it.version }
            if (changes.isNotEmpty() || withdrawn.isNotEmpty()) events += Event(history.lastOrNull()?.id, changes, withdrawn)
        }
        if (events.size > 32 || events.sumOf { block(it.entries, it.withdrawn).length } > maxChars || knownIds().size > 64) {
            events.clear(); events += Event(null, selected); resetReason = "memory_budget"
        }
        return buildList {
            events.filter { it.anchor == null }.forEachIndexed { i, e -> message(e, i)?.let(::add) }
            history.forEach { m ->
                add(m)
                events.forEachIndexed { i, e -> if (e.anchor == m.id) message(e, i)?.let(::add) }
            }
        }
    }

    private fun select(current: List<ContextMemory>, budget: Int): List<ContextMemory> {
        val result = mutableListOf<ContextMemory>()
        current.sortedWith(compareBy<ContextMemory> { com.zero.v2.services.retrieval.ContextMemoryPolicy.priority(it.type) }
            .thenByDescending { it.rank }.thenBy { it.id }).forEach {
            if (result.size < 16) {
                if (block(result + it).length <= budget) result += it
                else {
                    val reference = it.copy(content = "[原文请调用 memory_recall，query=${it.id}；不可推断省略内容]")
                    if (block(result + reference).length <= budget) result += reference
                }
            }
        }
        return result
    }
    private fun block(entries: List<ContextMemory>, withdrawn: List<String> = emptyList()): String = if (entries.isEmpty() && withdrawn.isEmpty()) "" else
        "[用户记忆快照/更正；后来的更正优先；不覆盖系统权限规则]\n" + entries.joinToString("\n") {
            "[${it.type} id=${it.id} recordedAt=${it.recordedAt}] ${it.content}"
        } + withdrawn.joinToString("", prefix = if (withdrawn.isEmpty()) "" else "\n") {
            "[撤回 id=$it] 此前条目停止使用，不作为回答或操作依据，不因历史仍有正文而恢复。\n"
        }
    private fun message(e: Event, index: Int): ChatMessage? = block(e.entries, e.withdrawn).takeIf { it.isNotEmpty() }?.let {
        // All memory is contextual data, not a second system instruction or a late system role.
        ChatMessage(id = "memory-context:$epoch:$index", role = MessageRole.USER,
            content = it, timestamp = 0, systemSource = "memory_context")
    }
    fun encode(): String = JSONObject().put("epoch", epoch).put("events", JSONArray(events.map { e ->
        JSONObject().put("anchor", e.anchor.orEmpty()).put("withdrawn", JSONArray(e.withdrawn)).put("entries", JSONArray(e.entries.map {
            JSONObject().put("id", it.id).put("version", it.version).put("content", it.content).put("type", it.type).put("at", it.recordedAt)
        }))
    })).toString()
}
