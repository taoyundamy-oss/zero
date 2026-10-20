package com.zero.v2.framework.chat

import com.zero.v2.core.common.ChatMessage
import com.zero.v2.core.common.MessageRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test

class ToolResultAccessTest {
    private fun result(text: String) = ChatMessage(id = "result", role = MessageRole.TOOL, content = text, toolCallId = "call")
    @Test fun `large result is bounded before entering the next request without losing original`() {
        val text = "header\n" + "large output\n".repeat(10000) + "critical tail"
        val original = ChatMessage(id = "result", role = MessageRole.TOOL, content = text, toolCallId = "call")
        val projected = ToolResultAccess.project(original)
        assertTrue("Large result still consumes the entire context", projected.content!!.length < 6000)
        assertTrue(projected.content!!.contains("read_tool_result"))
        assertTrue(projected.content!!.contains("critical tail"))
        assertEquals(text, original.content)
        assertEquals("call", projected.toolCallId)
    }

    @Test fun `small results and user messages remain exact`() {
        val small = result("{\"ok\":false,\"error\":\"permission denied\"}")
        assertEquals(small, ToolResultAccess.project(small))
        val user = small.copy(role = MessageRole.USER, content = "x".repeat(50000))
        assertEquals(user, ToolResultAccess.project(user))
    }

    @Test fun `all unicode pages reconstruct exact original and are never reprojected`() {
        val text = "中文😀\n".repeat(9000)
        val original = result(text)
        val ref = ToolResultAccess.reference(original)
        val restored = listOf(original.copy())
        val pages = StringBuilder()
        val total = text.codePointCount(0, text.length)
        for (offset in 0 until total step 6000) {
            val page = ToolResultAccess.read(restored, ref, offset, 6000)
            pages.append(page.substringAfter('\n'))
            assertEquals(page, ToolResultAccess.project(result(page)).content)
        }
        assertEquals(text, pages.toString())
        assertTrue(ToolResultAccess.read(restored, ref, total).contains("eof=true"))
    }

    @Test fun `middle error can be located without rerunning side effect`() {
        val original = result("x".repeat(30000) + "ERROR code=42 rollback=no" + "y".repeat(30000))
        val page = ToolResultAccess.read(listOf(original), ToolResultAccess.reference(original), query = "ERROR")
        assertTrue(page.contains("offset=30000"))
        assertTrue(page.contains("ERROR code=42 rollback=no"))
        assertTrue(ToolResultAccess.read(listOf(original), ToolResultAccess.reference(original), query = "missing").contains("未匹配"))
    }

    @Test fun `deleted edited cross conversation and invalid ranges are rejected`() {
        val original = result("original")
        val ref = ToolResultAccess.reference(original)
        assertTrue(runCatching { ToolResultAccess.read(emptyList(), ref) }.isFailure)
        assertTrue(runCatching { ToolResultAccess.read(listOf(original.copy(content = "edited")), ref) }.isFailure)
        assertTrue(runCatching { ToolResultAccess.read(listOf(original), ref, -1) }.isFailure)
        assertTrue(runCatching { ToolResultAccess.read(listOf(original), ref, Int.MAX_VALUE) }.isFailure)
        assertTrue(runCatching { ToolResultAccess.read(listOf(original), ref, limit = Int.MAX_VALUE) }.isFailure)
    }

    @Test fun `generation append and reconstruction retain same reference and preview`() {
        val original = listOf(result("row\n".repeat(20000)))
        val base = original.map(ToolResultAccess::project)
        val state = GenerationProjection()
        state.freeze(base, base, "scope")
        val next = original + ChatMessage(id = "next", role = MessageRole.USER, content = "continue")
        val restored = GenerationProjection(state.snapshot())
        assertEquals(base, restored.view(next.map(ToolResultAccess::project), "scope").take(1))
        assertFalse(base.single().content!!.contains("已瘦身"))
        assertEquals(ToolResultAccess.tools(emptyList()), ToolResultAccess.tools(ToolResultAccess.tools(emptyList())))
    }
}
