package com.zero.v2.services.llm

import com.zero.v2.core.common.ChatMessage
import com.zero.v2.core.common.MessageRole
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReasoningReplayTest {
    @Test fun `legacy assistant requires explicit reasoning field even without tool calls`() {
        val wire = JSONObject()
        ReasoningReplay.apply(wire, ChatMessage(role = MessageRole.ASSISTANT, content = "legacy"), true)
        assertTrue("DeepSeek rejects missing reasoning_content in tool-enabled history", wire.has("reasoning_content"))
        assertEquals("", wire.getString("reasoning_content"))
    }
}
