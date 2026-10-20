package com.zero.v2.core.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolTranscriptTest {
    private val assistant = ChatMessage(id = "a", role = MessageRole.ASSISTANT, content = "",
        toolCalls = listOf(ToolCall("c1", "first", "{}"), ToolCall("c2", "second", "{}")))

    @Test fun `interrupted batch gets explicit unknown results before next user`() {
        val partial = listOf(assistant, ChatMessage(id = "r1", role = MessageRole.TOOL, content = "ok", toolCallId = "c1"),
            ChatMessage(id = "u2", role = MessageRole.USER, content = "continue"))
        val repaired = ToolTranscript.repair(partial)
        assertEquals(listOf("c1", "c2"), repaired.drop(1).takeWhile { it.role == MessageRole.TOOL }.map { it.toolCallId })
        assertEquals("ok", repaired[1].content)
        assertTrue(repaired[2].content!!.contains("未取得结果"))
        assertEquals(repaired, ToolTranscript.repair(repaired))
    }

    @Test fun `orphan and duplicate results cannot enter request`() {
        val result = ChatMessage(role = MessageRole.TOOL, content = "ok", toolCallId = "c1")
        val repaired = ToolTranscript.repair(listOf(result, assistant.copy(toolCalls = assistant.toolCalls.take(1)), result, result))
        assertEquals(2, repaired.size)
        assertEquals(MessageRole.ASSISTANT, repaired.first().role)
    }
}
