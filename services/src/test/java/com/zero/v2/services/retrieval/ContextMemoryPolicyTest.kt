package com.zero.v2.services.retrieval

import com.zero.v2.core.common.MemoryType
import com.zero.v2.core.common.TaskMemory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ContextMemoryPolicyTest {
    @Test fun explicitKindsAndTaskStatesAreNotGuessedFromText() {
        assertTrue(ContextMemoryPolicy.accepts(MemoryType.DIRECTIVE, "规范", 0f, 0))
        assertTrue(ContextMemoryPolicy.accepts(MemoryType.RELATIONSHIP, "关系", 0f, 0))
        for (status in TaskMemory.STATUSES) {
            val task = TaskMemory(status, "检查", "一致", null, "任务")
            assertEquals(task.active, ContextMemoryPolicy.accepts(MemoryType.TASK, task.encode(), 1f, 10))
        }
        assertFalse(ContextMemoryPolicy.accepts(MemoryType.TASK, "正在进行", 1f, 10))
        assertFalse(ContextMemoryPolicy.accepts(MemoryType.EVENT, "已发生", 1f, 10))
    }
    @Test fun factsAndPreferencesUseExplicitImportanceOrRealAccess() {
        for (type in listOf(MemoryType.FACT, MemoryType.PREFERENCE)) {
            assertFalse(ContextMemoryPolicy.accepts(type, "内容", .7f, 1))
            assertTrue(ContextMemoryPolicy.accepts(type, "内容", .8f, 0))
            assertTrue(ContextMemoryPolicy.accepts(type, "内容", .1f, 2))
        }
    }
}
