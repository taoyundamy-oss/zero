package com.zero.v2.framework.chat

import com.zero.v2.core.common.FunctionDefinition
import com.zero.v2.core.common.PromptPrefix

/** A deterministic small manifest, independent of the current user utterance. */
internal object CoreToolCatalog {
    const val BYTE_LIMIT = 24_000
    private val names = listOf(
        "memory_recall", "memory_remember", "memory_sources", "memory_forget", "kb_search",
        "skill_search", "skill_read", "skill_write", "skill_delete",
        "music_search", "music_play", "music_pause", "music_status",
        "calendar_list_events", "calendar_create_event", "set_alarm", "set_timer",
    )

    fun select(catalog: List<FunctionDefinition>): List<FunctionDefinition> {
        val byName = catalog.associateBy { it.name }
        val selected = mutableListOf<FunctionDefinition>()
        var bytes = 0
        for (name in names) {
            val tool = byName[name] ?: continue
            val cost = PromptPrefix.toolManifest(listOf(tool)).toByteArray(Charsets.UTF_8).size
            if (bytes + cost <= BYTE_LIMIT) { selected += tool; bytes += cost }
        }
        return selected.sortedBy { it.name }
    }
}
