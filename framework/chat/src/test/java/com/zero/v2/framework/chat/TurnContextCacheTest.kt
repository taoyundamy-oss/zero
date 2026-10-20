package com.zero.v2.framework.chat

import com.zero.v2.core.common.ChatMessage
import com.zero.v2.core.common.MessageRole
import org.junit.Assert.assertEquals
import org.junit.Test

class TurnContextCacheTest {
    @Test fun `restored context is not duplicated or overwritten`() {
        val original = projection.inject(listOf(ChatMessage(id = "u1", role = MessageRole.USER, content = "hello")), "old")
        val restored = TurnContextProjection().inject(original, "new")
        assertEquals(original, restored)
    }
    private val projection = TurnContextProjection()
    private fun wire(messages: List<ChatMessage>) = messages.map { it.role to it.content }

    @Test fun `tool continuation preserves every previous request message`() {
        val user = ChatMessage(id = "u1", role = MessageRole.USER, content = "查询设备")
        val first = projection.inject(listOf(user), "本轮角色风格及证据")
        val continuation = projection.inject(
            listOf(user, ChatMessage(role = MessageRole.ASSISTANT, content = "调用工具"),
                ChatMessage(role = MessageRole.TOOL, content = "设备在线", toolCallId = "call1")),
            "本轮角色风格及证据",
        )
        assertEquals("FC request rewrote the already cached prefix", wire(first), wire(continuation.take(first.size)))
    }

    @Test fun `next user turn preserves earlier context snapshot`() {
        val user = ChatMessage(id = "u1", role = MessageRole.USER, content = "你好")
        val first = projection.inject(listOf(user), "风格一")
        val next = projection.inject(
            listOf(user, ChatMessage(role = MessageRole.ASSISTANT, content = "你好"),
                ChatMessage(id = "u2", role = MessageRole.USER, content = "继续")), "风格二",
        )
        assertEquals("next turn deleted a cached context message", wire(first), wire(next.take(first.size)))
    }
}
