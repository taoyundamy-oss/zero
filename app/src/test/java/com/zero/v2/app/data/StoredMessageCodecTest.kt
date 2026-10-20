package com.zero.v2.app.data

import com.zero.v2.core.common.ChatMessage
import com.zero.v2.core.common.MessageRole
import com.zero.v2.core.common.ToolCall
import org.junit.Assert.assertEquals
import org.junit.Test

class StoredMessageCodecTest {
    @Test fun `native signed content survives storage without tools or visible thinking`() {
        val state = com.zero.v2.core.common.ProviderContentState("route", """[{"type":"thinking","thinking":"","signature":"abc+/="},{"type":"redacted_thinking","data":"opaque"}]""")
        val message = ChatMessage(role = MessageRole.ASSISTANT, content = "answer", providerContent = state)
        assertEquals(message, StoredMessageCodec.restore(message.copy(providerContent = null), StoredMessageCodec.encode(message)))
    }
    @Test fun `assistant reasoning and tool ids survive database metadata round trip`() {
        val message = ChatMessage(id = "a", role = MessageRole.ASSISTANT, content = "",
            thinking = "reasoning", toolCalls = listOf(ToolCall("call1", "invoke_tool", "{\"name\":\"设备\"}",
                com.zero.v2.core.common.ToolProviderState("scope", "signature+/=exact"), 0)))
        val restored = StoredMessageCodec.restore(message.copy(thinking = null, toolCalls = emptyList()), StoredMessageCodec.encode(message))
        assertEquals(message, restored)
        val tool = ChatMessage(id = "t", role = MessageRole.TOOL, content = "result", toolCallId = "call1", toolCallName = "invoke_tool")
        assertEquals(tool, StoredMessageCodec.restore(tool.copy(toolCallId = null, toolCallName = null), StoredMessageCodec.encode(tool)))
    }

    @Test fun `legacy tool arrays are readable`() {
        val restored = StoredMessageCodec.restore(ChatMessage(role = MessageRole.ASSISTANT, content = ""),
            """[{"id":"old","function":{"name":"legacy","arguments":"{}"}}]""")
        assertEquals(listOf(ToolCall("old", "legacy", "{}")), restored.toolCalls)
    }
}
