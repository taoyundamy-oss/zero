package com.zero.v2.app.ui.settings

import com.zero.v2.core.common.MemoryType
import com.zero.v2.core.data.entity.MemoryEntity
import com.zero.v2.core.data.entity.MessageEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PreferenceSuggestionsTest {
    private fun memory(id: String, text: String = "写作时我喜欢安静；讨论时不适用。") = MemoryEntity(
        id, text, type = MemoryType.PREFERENCE, origin = "USER_QUOTE", sourceMessageId = id,
        sourceConversationId = "conversation", createdAt = 10)
    private fun messages(rows: List<MemoryEntity>) = rows.associate { it.id to MessageEntity(
        it.id, "conversation", "user", it.content, source = "CHAT_WINDOW") }

    @Test fun pendingConflictAndFutureValidityDoNotBecomeSuggestions() {
        val rows = listOf(memory("one"), memory("two"))
        assertTrue(PreferenceSuggestions.generate(rows, messages(rows), 100, conflicts = setOf("two")).isEmpty())
        val future = listOf(rows.first(), rows.last().copy(validFrom = 101))
        assertTrue(PreferenceSuggestions.generate(future, messages(future), 100).isEmpty())
    }
    @Test fun processedVersionDoesNotReappearAsAutomaticSuggestion() {
        val rows = listOf(memory("one"), memory("two"))
        val reviewed = setOf(com.zero.v2.core.data.entity.MemoryConflictEntity.version(rows.last()))
        assertTrue(PreferenceSuggestions.generate(rows, messages(rows), 100, reviewed).isEmpty())
    }
    @Test fun independentRepeatedStatementsPreserveEntireConditionAndDoNotWrite() {
        val rows = listOf(memory("one"), memory("two"))
        val found = PreferenceSuggestions.generate(rows, messages(rows), 100).single()
        assertEquals(rows.last().content, found.memory.content)
        assertEquals(listOf("one", "two"), found.supportingMessages)
        assertEquals("USER_QUOTE", found.memory.origin)
    }
    @Test fun duplicateMessageAndDifferentScopeCannotCreateGeneralizedPreference() {
        val first = memory("one")
        assertTrue(PreferenceSuggestions.generate(listOf(first, first.copy(id = "duplicate")), messages(listOf(first)), 100).isEmpty())
        val scoped = listOf(first, memory("two").copy(scope = "other"))
        assertTrue(PreferenceSuggestions.generate(scoped, messages(scoped), 100).isEmpty())
    }
    @Test fun unknownDirectiveRetiredChangedOrSystemSourcesAreExcluded() {
        val first = memory("one")
        for (second in listOf(memory("two").copy(origin = "UNKNOWN"),
            memory("two").copy(type = MemoryType.DIRECTIVE), memory("two").copy(validUntil = 100))) {
            val rows = listOf(first, second)
            assertTrue(PreferenceSuggestions.generate(rows, messages(rows), 100).isEmpty())
        }
        val rows = listOf(first, memory("two"))
        for (bad in listOf(messages(rows).getValue("two").copy(content = "已更正"),
            messages(rows).getValue("two").copy(systemSource = "system"))) {
            assertTrue(PreferenceSuggestions.generate(rows, messages(rows) + ("two" to bad), 100).isEmpty())
        }
    }
}
