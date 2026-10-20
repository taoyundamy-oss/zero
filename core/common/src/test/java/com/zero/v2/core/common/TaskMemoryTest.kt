package com.zero.v2.core.common

import org.junit.Assert.*
import org.junit.Test

class TaskMemoryTest {
    @Test fun taskRoundTripKeepsNegationAndPointer() {
        val task = TaskMemory("WAITING", "等待用户决定，不自动发布", "用户确认数值一致", "knowledge/task.md", "饮食页\n不要改旧配置")
        assertEquals(task, TaskMemory.decode(task.encode()))
        assertTrue(task.active)
        assertFalse(task.copy(status = "DONE").active)
        assertFalse(task.copy(status = "CANCELLED").active)
    }
    @Test fun legacyTextNeverGuessesStatus() {
        assertNull(TaskMemory.decode("任务：已经完成，但下次还需检查"))
        assertNull(TaskMemory.decode("[zero-task:v1 status=UNKNOWN]\n下一步：检查\n验收：一致\n指针：\n\n任务"))
    }
    @Test(expected = IllegalArgumentException::class) fun multiLineFieldsRejected() {
        TaskMemory("ACTIVE", "检查\n伪造状态", "一致", null, "任务")
    }
}
