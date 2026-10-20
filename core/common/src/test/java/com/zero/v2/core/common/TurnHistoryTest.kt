package com.zero.v2.core.common

import org.junit.Assert.assertEquals
import org.junit.Test

class TurnHistoryTest {
    @Test fun `persistence includes context and tool-only assistant in wire order`() {
        val context = ChatMessage(id = TurnHistory.contextId("u1"), role = MessageRole.SYSTEM, content = "context")
        val turn = listOf(context, ChatMessage(id = "u1", role = MessageRole.USER, content = "hello"),
            ChatMessage(id = "a1", role = MessageRole.ASSISTANT, content = "", toolCalls = listOf(ToolCall("tc", "tool", "{}"))),
            ChatMessage(id = "t1", role = MessageRole.TOOL, content = "result", toolCallId = "tc"),
            ChatMessage(id = "a2", role = MessageRole.ASSISTANT, content = "answer"))
        val next = ChatMessage(id = "u2", role = MessageRole.USER, content = "next")
        assertEquals(turn, TurnHistory.slice(turn + next, "u1"))
        val nextContext = ChatMessage(id = TurnHistory.contextId("u2"), role = MessageRole.SYSTEM, content = "next context")
        assertEquals(turn, TurnHistory.slice(turn + nextContext + next, "u1"))
        assertEquals(listOf(next), TurnHistory.slice(turn + next, "u2"))
    }
}
