package com.zero.v2.framework.memory

import com.zero.v2.core.common.ChatFinishedEvent
import com.zero.v2.core.common.ChatMessage
import com.zero.v2.core.common.MessageRole
import com.zero.v2.core.common.MessageSource
import com.zero.v2.core.common.TokenUsage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MemorySourcePolicyTest {
    private fun event(source: MessageSource, original: String? = "提醒类消息请发微信") = ChatFinishedEvent(
        ChatMessage("user-id", MessageRole.USER, "运行提示：用户随时可能打断。$original", source = source),
        ChatMessage("assistant-id", MessageRole.ASSISTANT, "用户偏好提醒类消息通过微信送达"),
        emptyList(), TokenUsage(), "conversation-id", original,
    )

    @Test fun syntheticTurnsNeverBecomeUserFacts() {
        listOf(MessageSource.AUTONOMOUS, MessageSource.AUTOMATION, MessageSource.POKE).forEach {
            assertNull(MemorySourcePolicy.userInput(event(it)))
        }
    }

    @Test fun realUserInputExcludesPipelineGuidanceAndAssistantParaphrase() {
        assertEquals("提醒类消息请发微信", MemorySourcePolicy.userInput(event(MessageSource.CHAT_WINDOW)))
    }

    @Test fun missingOriginalInputCannotFallBackToProcessedPrompt() {
        assertNull(MemorySourcePolicy.userInput(event(MessageSource.CHAT_WINDOW, null)))
    }
}
