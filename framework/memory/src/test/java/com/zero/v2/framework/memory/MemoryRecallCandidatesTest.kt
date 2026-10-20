package com.zero.v2.framework.memory

import com.zero.v2.core.data.entity.MemoryEntity
import org.junit.Assert.*
import org.junit.Test

class MemoryRecallCandidatesTest {
    @Test fun questionsAreNotAnswersButRemainAvailableForQuestionHistory() {
        val question = MemoryEntity("q", "嗯，你可知道我最近在做什么，不妨整理一下")
        val fact = MemoryEntity("f", "我最近在学习如何制作软件。")
        val answer = MemoryEntity("a", "我最近有什么日程？明天开会。")
        assertEquals(listOf(fact, answer), MemoryRecallCandidates.select("我最近在做什么", listOf(question, fact, answer)))
        assertEquals(listOf(question), MemoryRecallCandidates.select("我问过哪些问题", listOf(question)))
        val sourced = question.copy(sourceConversationId = "source")
        assertEquals(listOf(sourced), MemoryRecallCandidates.select("最近", listOf(sourced)))
    }

    @Test fun duplicatePunctuationDoesNotConsumeSlotsAndNegationRemainsDistinct() {
        val first = MemoryEntity("a", "提醒请发微信")
        val duplicate = first.copy(id = "b", content = "提醒请发微信。")
        val opposite = first.copy(id = "c", content = "提醒不要发微信。")
        assertEquals(listOf(first, opposite), MemoryRecallCandidates.select("提醒", listOf(first, duplicate, opposite)))
    }
}
