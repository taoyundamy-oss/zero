package com.zero.v2.app.ui.automation

import com.zero.v2.core.common.ToolCall
import com.zero.v2.core.common.FunctionResult
import com.zero.v2.core.moduleapi.ToolDispatcher
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class AutomationCommandDispatcherTest {
    @Test
    fun `typed draft command maps to the canonical tool contract`() = runBlocking {
        val fake = RecordingDispatcher()
        AutomationCommandDispatcher(fake).execute(AutomationCommand.Draft("整理收件箱"))

        assertEquals("draft_automation", fake.name)
        assertEquals("整理收件箱", fake.arguments["description"])
    }

    private class RecordingDispatcher : ToolDispatcher {
        var name: String = ""
        var arguments: Map<String, Any?> = emptyMap()

        override suspend fun execute(name: String, arguments: Map<String, Any?>): FunctionResult {
            this.name = name; this.arguments = arguments
            return FunctionResult.success("ok")
        }
        override suspend fun execute(toolCall: ToolCall) = FunctionResult.success("ok")
        override suspend fun executeBatch(toolCalls: List<ToolCall>) = toolCalls.map { FunctionResult.success("ok") }
    }
}
