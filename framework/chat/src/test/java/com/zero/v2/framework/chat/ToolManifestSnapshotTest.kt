package com.zero.v2.framework.chat

import com.zero.v2.core.common.FunctionDefinition
import com.zero.v2.core.common.ChatMessage
import com.zero.v2.core.common.MessageRole
import com.zero.v2.core.moduleapi.ToolContextRequest
import com.zero.v2.core.moduleapi.ToolContextSelector
import org.junit.Assert.*
import org.junit.Test

class ToolManifestSnapshotTest {
    @Test fun `scope contract upgrades version two once and retains same version frozen schemas`() {
        val state = ToolManifestSnapshot.checkpoint(1, emptyList())
        state.setProperty("contractVersion", "2")
        assertEquals(null, ToolManifestSnapshot.restore(state, 1))
        state.setProperty("contractVersion", ToolManifestSnapshot.CONTRACT_VERSION)
        assertEquals(emptyList<FunctionDefinition>(), ToolManifestSnapshot.restore(state, 1))
    }
    @Test fun `incompatible legacy contract is rebuilt once while current checkpoint stays frozen`() {
        val frozen = listOf(FunctionDefinition("memory_remember", "new source-bound contract", mapOf("type" to "object")))
        val legacy = java.util.Properties().apply {
            setProperty("epoch", "7"); setProperty("tools", ToolManifestSnapshot.encode(frozen))
        }
        assertNull(ToolManifestSnapshot.restore(legacy, 7))
        legacy.setProperty("contractVersion", "1")
        assertNull(ToolManifestSnapshot.restore(legacy, 7))
        val current = ToolManifestSnapshot.checkpoint(7, frozen)
        repeat(3) { assertEquals(frozen, ToolManifestSnapshot.restore(current, 7)) }
        assertNull(ToolManifestSnapshot.restore(current, 8))
    }
    private val catalog = listOf("memory_recall", "music_play", "rare", "expired", "pinned").map {
        FunctionDefinition(it, it, mapOf("type" to "object", "properties" to mapOf("x" to mapOf("type" to "string"))))
    }
    @Test fun `recent and pinned extras survive roundtrip without changing the prefix`() {
        val first = ToolManifestSnapshot.choose(catalog, mapOf("rare" to 1L), setOf("pinned"), 2L)
        val saved = ToolManifestSnapshot.encode(first)
        assertEquals(first, ToolManifestSnapshot.decode(saved))
        assertEquals(listOf("memory_recall", "music_play", "pinned", "rare"), first.map { it.name })
        val next = ToolManifestSnapshot.choose(catalog, mapOf("rare" to 1L), setOf("pinned"), ToolManifestSnapshot.RECENT_MS + 2)
        assertFalse(next.any { it.name == "rare" })
        assertTrue(ToolManifestSnapshot.decode(saved).any { it.name == "rare" })
    }
    @Test fun `generation epoch persists and only advances on freeze`() {
        val p = GenerationProjection()
        val message = ChatMessage(id = "a", role = MessageRole.USER, content = "a")
        p.view(listOf(message), "scope")
        assertEquals(0L, p.epoch)
        p.freeze(listOf(message), listOf(message), "scope")
        val restored = GenerationProjection(p.snapshot())
        assertEquals(1L, restored.epoch)
        restored.view(listOf(message), "new scope")
        assertEquals(1L, restored.epoch)
    }
    @Test fun `frozen schemas remain stable but stale direct execution is rejected`() {
        var current = catalog
        val selector = object : ToolContextSelector { override fun select(request: ToolContextRequest) = current }
        val frozen = ToolManifestSnapshot.choose(catalog, emptyMap(), emptySet(), 0)
        val session = ToolDiscoverySession(selector, emptyList(), 8, frozen)
        current = emptyList()
        assertEquals(frozen, session.tools.drop(2))
        assertTrue(runCatching { session.resolve(com.zero.v2.core.common.ToolCall("a", "music_play", "{}")) }.isFailure)
        assertEquals(0, org.json.JSONObject(session.discover("", 0, "music")).getInt("total"))
        current = catalog
        assertEquals(1, org.json.JSONObject(session.discover("", 0, "music")).getInt("total"))
    }
    @Test fun `all core tools and eight extras cannot overflow the persisted row limit`() {
        val coreNames = listOf("memory_recall", "memory_remember", "memory_sources", "memory_forget", "kb_search",
            "skill_search", "skill_read", "skill_write", "skill_delete", "music_search", "music_play", "music_pause",
            "music_status", "calendar_list_events", "calendar_create_event", "set_alarm", "set_timer")
        val extras = (1..8).map { "extra_$it" }
        val all = (coreNames + extras).map { FunctionDefinition(it, it, mapOf("type" to "object")) }
        val result = ToolManifestSnapshot.choose(all, extras.drop(4).associateWith { 1L }, extras.take(4).toSet(), 2L)
        assertTrue(result.size <= 24)
        assertTrue(result.map { it.name }.containsAll(coreNames))
        assertEquals(result, ToolManifestSnapshot.decode(ToolManifestSnapshot.encode(result)))
    }

}
