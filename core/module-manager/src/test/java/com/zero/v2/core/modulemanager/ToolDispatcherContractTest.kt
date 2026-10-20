package com.zero.v2.core.modulemanager

import com.zero.v2.core.moduleapi.ToolDispatcher

import com.zero.v2.core.common.FunctionResult
import com.zero.v2.core.common.ToolCall
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolDispatcherContractTest {
    @Test
    fun `dispatcher exposes stable single and batch execution seams`() = runBlocking {
        val dispatcher = RecordingDispatcher()
        val call = ToolCall("call_1", "demo", "{}")

        assertTrue(dispatcher.execute("demo", emptyMap()).success)
        assertTrue(dispatcher.execute(call).success)
        assertEquals(2, dispatcher.executeBatch(listOf(call, call)).size)
        assertEquals(listOf("name:demo", "call:call_1", "batch:2"), dispatcher.events)
    }

    private class RecordingDispatcher : ToolDispatcher {
        val events = mutableListOf<String>()

        override suspend fun execute(name: String, arguments: Map<String, Any?>): FunctionResult {
            events += "name:$name"
            return FunctionResult.success("ok")
        }

        override suspend fun execute(toolCall: ToolCall): FunctionResult {
            events += "call:${toolCall.id}"
            return FunctionResult.success("ok")
        }

        override suspend fun executeBatch(toolCalls: List<ToolCall>): List<FunctionResult> {
            events += "batch:${toolCalls.size}"
            return toolCalls.map { FunctionResult.success("ok") }
        }
    }
}
