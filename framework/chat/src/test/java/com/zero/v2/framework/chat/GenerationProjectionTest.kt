package com.zero.v2.framework.chat

import com.zero.v2.core.common.ChatMessage
import com.zero.v2.core.common.MessageRole
import org.junit.Assert.assertEquals
import org.junit.Test

class GenerationProjectionTest {
    @Test fun `generation invalidation distinguishes lost anchor and changed scope after restore`() {
        val old = ChatMessage(id = "old", role = MessageRole.USER, content = "old")
        val anchor = old.copy(id = "anchor", content = "retained")
        val projection = GenerationProjection()
        projection.freeze(listOf(old, anchor), listOf(anchor), "route")
        val restored = GenerationProjection(projection.snapshot())
        assertEquals(listOf(anchor), restored.view(listOf(old, anchor), "route"))
        assertEquals(null, restored.resetReason)
        assertEquals(listOf(old), restored.view(listOf(old), "route"))
        assertEquals("anchor_missing", restored.resetReason)
        restored.view(listOf(old), "route")
        assertEquals(null, restored.resetReason)
        restored.view(listOf(old), "other-route")
        assertEquals("scope_changed", restored.resetReason)
        val fresh = GenerationProjection()
        fresh.view(listOf(old), "route")
        assertEquals(null, fresh.resetReason)
    }

    @Test fun `raising budget cannot resurrect archived prefix after reconstruction`() {
        val messages = (0..5).map { ChatMessage(id = "id$it", role = MessageRole.USER, content = "original$it") }
        val projection = GenerationProjection()
        projection.freeze(messages, messages.takeLast(2), "same-profile")
        val restored = GenerationProjection(projection.snapshot())
        val next = ChatMessage(id = "next", role = MessageRole.USER, content = "new")
        assertEquals(messages.takeLast(2) + next, restored.view(messages + next, "same-profile"))
        assertEquals("original0", messages.first().content)
    }

    @Test fun `trimmed tool prefix survives next request and process reconstruction`() {
        val tool = ChatMessage(id = "tool", role = MessageRole.TOOL, content = "x".repeat(2000))
        val source = listOf(ChatMessage(id = "old", role = MessageRole.USER, content = "old"), tool)
        val retained = listOf(tool.copy(content = "x".repeat(100) + GenerationProjection.SUFFIX))
        val projection = GenerationProjection()
        projection.freeze(source, retained, "profile-prefix")
        val next = source + ChatMessage(id = "next", role = MessageRole.USER, content = "next")
        assertEquals(retained, projection.view(next, "profile-prefix").take(1))
        val bytes = java.io.ByteArrayOutputStream().also { projection.snapshot().store(it, null) }.toByteArray()
        val loaded = java.util.Properties().apply { load(bytes.inputStream()) }
        val restored = GenerationProjection(loaded)
        assertEquals(projection.view(next, "profile-prefix"), restored.view(next, "profile-prefix"))
        assertEquals(next, restored.view(next, "different-profile"))
    }

    @Test fun `edited and deleted originals cannot be resurrected from snapshot`() {
        val tool = ChatMessage(id = "tool", role = MessageRole.TOOL, content = "long original")
        val projection = GenerationProjection()
        projection.freeze(listOf(tool), listOf(tool.copy(content = "lo" + GenerationProjection.SUFFIX)), "s")
        val edited = tool.copy(content = "corrected")
        assertEquals(listOf(edited), projection.view(listOf(edited), "s"))
        assertEquals(emptyList<ChatMessage>(), projection.view(emptyList(), "s"))
    }
    @Test fun `production slicer result is frozen rather than rebuilt from long tool text`() {
        val messages = (0..7).map { index ->
            ChatMessage(id = "m$index", role = if (index == 2) MessageRole.TOOL else MessageRole.USER,
                content = if (index == 2) "x".repeat(2000) else "short")
        }
        val slice = ContextWindow.sliceForGeneration(messages, 100, { 1 }, true, 100)
        val projection = GenerationProjection()
        projection.freeze(messages, slice.messages, "scope")
        val next = messages + ChatMessage(id = "new", role = MessageRole.USER, content = "next")
        assertEquals(slice.messages, projection.view(next, "scope").take(slice.messages.size))
        assertEquals(100 + GenerationProjection.SUFFIX.length, projection.view(next, "scope")[2].content!!.length)
    }

}
