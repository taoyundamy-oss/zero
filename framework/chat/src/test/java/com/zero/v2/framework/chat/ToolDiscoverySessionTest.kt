package com.zero.v2.framework.chat

import com.zero.v2.core.common.FunctionDefinition
import com.zero.v2.core.moduleapi.ToolContextRequest
import com.zero.v2.core.moduleapi.ToolContextSelector
import org.junit.Assert.*
import org.junit.Test

class ToolDiscoverySessionTest {
    @Test fun `core schemas are directly exposed independent of intent and catalog ordering`() {
        var catalog = listOf(FunctionDefinition("music_play", "播放", mapOf("type" to "object")),
            FunctionDefinition("memory_recall", "记忆", mapOf("type" to "object")),
            FunctionDefinition("rare_tool", "不常用", emptyMap()))
        val selector = object : ToolContextSelector { override fun select(request: ToolContextRequest) = catalog }
        val first = ToolDiscoverySession(selector, catalog.takeLast(1), 4)
        assertEquals(listOf("discover_tools", "invoke_tool", "memory_recall", "music_play"), first.tools.map { it.name })
        catalog = catalog.reversed()
        assertEquals(first.tools, ToolDiscoverySession(selector, emptyList(), 4).tools)
        first.discover("rare_tool", 0)
        assertEquals(4, first.tools.size)
        val direct = com.zero.v2.core.common.ToolCall("same", "music_play", "{}")
        assertEquals(direct, first.resolve(direct))
    }

    @Test fun `large core schemas stay discoverable without exceeding stable manifest budget`() {
        val huge = FunctionDefinition("memory_recall", "x".repeat(CoreToolCatalog.BYTE_LIMIT + 1), emptyMap())
        val selector = object : ToolContextSelector { override fun select(request: ToolContextRequest) = listOf(huge) }
        val session = ToolDiscoverySession(selector, emptyList(), 8)
        assertEquals(2, session.tools.size)
        assertEquals(1, org.json.JSONObject(session.discover("memory_recall", 0)).getInt("total"))
    }
    @Test fun `real device multi keyword query finds vision without scanning unrelated pages`() {
        val vision = FunctionDefinition("dyn_vision_pro_vision_ocr", "读取图片中的文字", emptyMap())
        val catalog = (1..30).map { FunctionDefinition("dyn_other_$it", "read file", emptyMap()) } + vision
        val selector = object : ToolContextSelector {
            override fun select(request: ToolContextRequest) = catalog
        }
        val session = ToolDiscoverySession(selector, emptyList(), 8)
        for (query in listOf("vision ocr image read", "dyn ocr vision read", "图像 识别 图片 视觉")) {
            val page = org.json.JSONObject(session.discover(query, 0)).getJSONArray("tools")
            assertTrue("No tools for $query", page.length() > 0)
            assertEquals(vision.name, page.getJSONObject(0).getString("name"))
        }
    }

    @Test fun `combined exact tool names are discoverable without changing cached schemas`() {
        val catalog = listOf(FunctionDefinition("attachment_export", "导出", emptyMap()),
            FunctionDefinition("attachment_publish", "展示", emptyMap()))
        val selector = object : ToolContextSelector {
            override fun select(request: ToolContextRequest) = catalog
        }
        val session = ToolDiscoverySession(selector, emptyList(), 8)
        val prefix = session.tools
        assertEquals(2, org.json.JSONObject(session.discover("attachment_export attachment_publish", 0)).getInt("total"))
        assertEquals(prefix, session.tools)
    }
    @Test fun `wrapped discovery resolves to the local discovery handler`() {
        val selector = object : ToolContextSelector {
            override fun select(request: ToolContextRequest) = emptyList<FunctionDefinition>()
        }
        val session = ToolDiscoverySession(selector, emptyList(), 8)
        val call = com.zero.v2.core.common.ToolCall("wrapped", "invoke_tool",
            """{"name":"discover_tools","arguments":{"query":"vision"}}""")
        assertEquals("discover_tools", session.resolve(call).name)
        assertEquals("wrapped", session.resolve(call).id)
    }
    @Test fun `invoke preserves call identity and rejects a disabled tool`() {
        var enabled = true
        val schema = mapOf("type" to "object", "required" to listOf("path"), "properties" to mapOf("path" to mapOf("type" to "string")))
        val selector = object : ToolContextSelector {
            override fun select(request: ToolContextRequest) = if (enabled) listOf(FunctionDefinition("read_file", "read", schema)) else emptyList()
        }
        val session = ToolDiscoverySession(selector, emptyList(), 8)
        assertTrue(session.discover("read", 0).contains("required"))
        val call = com.zero.v2.core.common.ToolCall("tc1", "invoke_tool", """{"name":"read_file","arguments":{"path":"a.txt"}}""")
        assertEquals("tc1", session.resolve(call).id)
        assertEquals("read_file", session.resolve(call).name)
        enabled = false
        assertTrue(runCatching { session.resolve(call) }.isFailure)
    }
    @Test fun `discovery and changing intent must not replace provider tool prefix`() {
        val catalog = listOf(FunctionDefinition("alpha", "first", emptyMap()), FunctionDefinition("beta", "second", emptyMap()))
        val selector = object : ToolContextSelector {
            override fun select(request: ToolContextRequest) = catalog
        }
        val first = ToolDiscoverySession(selector, catalog.take(1), 4)
        val prefix = first.tools
        first.discover("beta", 0)
        assertEquals("discovery changes cached schema prefix", prefix, first.tools)
        assertEquals("user intent changes cached schema prefix", prefix, ToolDiscoverySession(selector, catalog.takeLast(1), 4).tools)
    }
    @Test fun `empty initial match still allows paged discovery and disabled tools disappear`() {
        var catalog = (1..20).map { FunctionDefinition("tool_${it.toString().padStart(2, '0')}", "工具", emptyMap()) }
        val selector = object : ToolContextSelector {
            override fun select(request: ToolContextRequest) = catalog
        }
        val session = ToolDiscoverySession(selector, emptyList(), 4)
        assertEquals(listOf("discover_tools", "invoke_tool"), session.tools.map { it.name })
        val page = org.json.JSONObject(session.discover("", 0))
        assertEquals(3, page.getInt("nextOffset"))
        assertEquals("tool_01", page.getJSONArray("tools").getJSONObject(0).getString("name"))
        catalog = catalog.filterNot { it.name == "tool_09" }
        assertEquals(0, org.json.JSONObject(session.discover("tool_09", 0)).getInt("total"))
        assertEquals(2, session.tools.size)
        assertTrue(session.discover("", 18).contains("tool_20"))
    }
}
