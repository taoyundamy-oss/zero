package com.zero.v2.core.bus

import com.zero.v2.core.common.ChatSendEvent
import com.zero.v2.core.common.MessageSource
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MessagePipelineTest {

    private val sourceEvent = ChatSendEvent("hello", MessageSource.CHAT_WINDOW)

    @Test
    fun `slow filter is skipped and later filters still run`() = runBlocking {
        val recorder = MessagePipelineTraceRecorder()
        val pipeline = MessagePipeline(recorder)
        pipeline.registerFilter(object : IMessageFilter {
            override val filterOrder = 0
            override suspend fun process(event: ChatSendEvent): ChatSendEvent {
                delay(150)
                return event.copy(message = "should-not-apply")
            }
        })
        pipeline.registerFilter(object : IMessageFilter {
            override val filterOrder = 1
            override suspend fun process(event: ChatSendEvent): ChatSendEvent =
                event.copy(message = event.message + "!")
        })

        val result = pipeline.process(sourceEvent)

        assertEquals("hello!", result.message)
        assertEquals(1, recorder.snapshot().count { it.outcome == FilterOutcome.TIMEOUT })
        assertEquals(1, recorder.snapshot().count { it.outcome == FilterOutcome.COMPLETED })
    }

    @Test
    fun `filter exception is isolated and later filters still run`() = runBlocking {
        val recorder = MessagePipelineTraceRecorder()
        val pipeline = MessagePipeline(recorder)
        pipeline.registerFilter(object : IMessageFilter {
            override val filterOrder = 0
            override suspend fun process(event: ChatSendEvent): ChatSendEvent =
                error("synthetic filter failure")
        })
        pipeline.registerFilter(object : IMessageFilter {
            override val filterOrder = 1
            override suspend fun process(event: ChatSendEvent): ChatSendEvent =
                event.copy(message = "recovered")
        })

        val result = pipeline.process(sourceEvent)

        assertEquals("recovered", result.message)
        assertEquals(1, recorder.snapshot().count { it.outcome == FilterOutcome.ERROR })
        assertTrue(recorder.snapshot().any { it.errorType == IllegalStateException::class.java.name })
    }

    @Test
    fun `filters execute in order and unregister is synchronized with snapshots`() = runBlocking {
        val pipeline = MessagePipeline(MessagePipelineTraceRecorder())
        val execution = mutableListOf<Int>()
        val second = object : IMessageFilter {
            override val filterOrder = 20
            override suspend fun process(event: ChatSendEvent): ChatSendEvent {
                execution += 20
                return event
            }
        }
        val first = object : IMessageFilter {
            override val filterOrder = 10
            override suspend fun process(event: ChatSendEvent): ChatSendEvent {
                execution += 10
                return event
            }
        }
        pipeline.registerFilter(second)
        pipeline.registerFilter(first)

        pipeline.process(sourceEvent)
        pipeline.unregisterFilter(first)
        pipeline.process(sourceEvent)

        assertEquals(listOf(10, 20, 20), execution)
    }
}
