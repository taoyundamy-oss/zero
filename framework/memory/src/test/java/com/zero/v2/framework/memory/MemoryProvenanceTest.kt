package com.zero.v2.framework.memory

import com.zero.v2.core.common.MemoryType
import com.zero.v2.core.data.entity.MemoryEntity
import org.junit.Assert.assertEquals
import org.junit.Test

class MemoryProvenanceTest {
    @Test fun confirmationCarriesScopeWithoutChangingLegacyPrefix() {
        val m = MemoryEntity("legacy", "使用柔和灯光", scope = "阅读时")
        assertEquals(m.content, memoryContextText(m))
        assertEquals("适用范围：阅读时\n使用柔和灯光", memoryContextText(m.copy(origin = "USER_CONFIRMED")))
        org.junit.Assert.assertTrue(memoryProvenance(m.copy(origin = "USER_CONFIRMED")).contains("阅读时"))
    }

    @Test fun conversationAssociationDoesNotAuthenticateLegacyFacts() {
        val legacy = MemoryEntity("legacy", "提醒走微信", type = MemoryType.FACT, sourceConversationId = "chat", confidence = 0.99f)
        assertEquals("历史记录，来源未核验", memoryProvenance(legacy))
        assertEquals("用户原文提取", memoryProvenance(legacy.copy(id = "user-fact:source")))
        assertEquals("历史记录，来源未核验", memoryProvenance(legacy.copy(id = "user-fact:source", sourceConversationId = null)))
    }
}
