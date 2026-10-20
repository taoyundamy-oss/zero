package com.zero.v2.app.ui.music

import com.zero.v2.core.common.ToolCall
import com.zero.v2.core.common.FunctionResult
import com.zero.v2.core.moduleapi.ToolDispatcher
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class MusicCommandDispatcherTest {
    @Test
    fun `typed seek command maps to canonical tool arguments`() = runBlocking {
        val fake = RecordingDispatcher()
        MusicCommandDispatcher(fake).execute(MusicCommand.Seek(1234L))
        assertEquals("music_seek", fake.name)
        assertEquals(1234L, fake.arguments["position_ms"])
    }

    private class RecordingDispatcher : ToolDispatcher {
        var name = ""
        var arguments: Map<String, Any?> = emptyMap()
        override suspend fun execute(name: String, arguments: Map<String, Any?>): FunctionResult {
            this.name = name; this.arguments = arguments; return FunctionResult.success("ok")
        }
        override suspend fun execute(toolCall: ToolCall) = FunctionResult.success("ok")
        override suspend fun executeBatch(toolCalls: List<ToolCall>) = toolCalls.map { FunctionResult.success("ok") }
    }
}
