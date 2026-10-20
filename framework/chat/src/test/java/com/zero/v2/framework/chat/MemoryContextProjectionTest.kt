package com.zero.v2.framework.chat

import com.zero.v2.core.common.ChatMessage
import com.zero.v2.core.common.MessageRole
import com.zero.v2.services.retrieval.ContextMemory
import org.junit.Assert.*
import org.junit.Test

class MemoryContextProjectionTest {
    private val a = ContextMemory("a", "v1", "以后请使用中文。", "DIRECTIVE", 1)
    private val user = ChatMessage(id = "u", role = MessageRole.USER, content = "你好")
    private fun bodies(messages: List<ChatMessage>) = messages.map { it.id to it.content }

    @Test fun stableHeadSurvivesRestartAndCorrectionsAppendAfterExistingHistory() {
        val p = MemoryContextProjection()
        val first = p.project(listOf(user), 0, listOf(a), 4000)
        val restored = MemoryContextProjection(p.encode())
        assertEquals(bodies(first), bodies(restored.project(listOf(user), 0, listOf(a), 4000)))
        val corrected = restored.project(listOf(user), 0, listOf(a.copy(version = "v2", content = "以后请使用英文。")), 4000)
        assertEquals(bodies(first), bodies(corrected.take(first.size)))
        assertTrue(corrected.last().content!!.contains("英文"))
        assertEquals(bodies(corrected), bodies(restored.project(listOf(user), 0, listOf(a.copy(version = "v2", content = "以后请使用英文。")), 4000)))
    }

    @Test fun withdrawalAppendsOnceSurvivesRestartAndNaturalGenerationCompacts() {
        val p = MemoryContextProjection()
        val original = p.project(listOf(user), 0, listOf(a), 4000)
        val removed = p.project(listOf(user), 0, emptyList(), 4000)
        assertEquals(bodies(original), bodies(removed.take(original.size)))
        assertTrue(removed.last().content!!.contains("撤回 id=a"))
        assertEquals(MessageRole.USER, removed.last().role)
        assertNull(p.resetReason)
        val restored = MemoryContextProjection(p.encode())
        assertEquals(bodies(removed), bodies(restored.project(listOf(user), 0, emptyList(), 4000)))
        assertEquals(listOf(user), restored.project(listOf(user), 1, emptyList(), 4000))
        assertEquals("memory_generation", restored.resetReason)
        com.zero.v2.services.llm.RequestContextPreparation.validateEvent(restored.resetReason!!)
    }

    @Test fun explicitReactivationAfterWithdrawalAddsEvenTheSameVersion() {
        val p = MemoryContextProjection()
        p.project(listOf(user), 0, listOf(a), 4000)
        val stopped = p.project(listOf(user), 0, emptyList(), 4000)
        val again = p.project(listOf(user), 0, listOf(a), 4000)
        assertEquals(bodies(stopped), bodies(again.take(stopped.size)))
        assertTrue(again.last().content!!.contains(a.content))
        assertEquals(bodies(again), bodies(p.project(listOf(user), 0, listOf(a), 4000)))
    }

    @Test fun prioritiesKeepNormAndRelationBeforeTaskAndFacts() {
        val p = MemoryContextProjection()
        val items = listOf(a.copy(id = "0", type = "FACT"), a.copy(id = "1", type = "TASK"),
            a.copy(id = "2", type = "RELATIONSHIP"), a.copy(id = "3"))
        val block = p.project(listOf(user), 0, items, 4000).first().content!!
        assertTrue(block.indexOf("id=3") < block.indexOf("id=2"))
        assertTrue(block.indexOf("id=2") < block.indexOf("id=1"))
        assertTrue(block.indexOf("id=1") < block.indexOf("id=0"))
    }

    @Test fun budgetKeepsWholeStatementOrRetrievableReferenceNeverPartialNegation() {
        val p = MemoryContextProjection()
        val huge = a.copy(content = "不要" + "很长的规范".repeat(1000))
        val result = p.project(listOf(user), 0, listOf(huge), 300)
        assertTrue(result.first().content!!.contains("memory_recall"))
        assertFalse(result.first().content!!.contains("很长的规范"))
        assertEquals(listOf(user), p.project(listOf(user), 0, listOf(huge), 0))
    }
}
