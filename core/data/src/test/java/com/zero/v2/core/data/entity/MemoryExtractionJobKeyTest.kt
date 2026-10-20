package com.zero.v2.core.data.entity

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R1：记忆提取作业幂等键 + 退避（纯逻辑，JVM 可测）。
 * 验收：进程退出续跑不重复写（幂等键）+ 失败退避（指数退避封顶）。
 */
class MemoryExtractionJobKeyTest {

    @Test
    fun `same source type and digest produce same key`() {
        val k1 = MemoryExtractionJobKey.compute(MemoryExtractionJobEntity.SOURCE_TYPE_AI_DEFERRED, "AI: 你好")
        val k2 = MemoryExtractionJobKey.compute(MemoryExtractionJobEntity.SOURCE_TYPE_AI_DEFERRED, "AI: 你好")
        assertEquals(k1, k2)
        assertTrue(k1.startsWith("AI_DEFERRED:"))
    }

    @Test
    fun `different digest produces different key`() {
        val k1 = MemoryExtractionJobKey.compute(MemoryExtractionJobEntity.SOURCE_TYPE_AI_DEFERRED, "AI: 内容A")
        val k2 = MemoryExtractionJobKey.compute(MemoryExtractionJobEntity.SOURCE_TYPE_AI_DEFERRED, "AI: 内容B")
        assertNotEquals(k1, k2)
    }

    @Test
    fun `different source type produces different key for same digest`() {
        val k1 = MemoryExtractionJobKey.compute(MemoryExtractionJobEntity.SOURCE_TYPE_AI_DEFERRED, "same content")
        val k2 = MemoryExtractionJobKey.compute(MemoryExtractionJobEntity.SOURCE_TYPE_USER_FACT, "same content")
        assertNotEquals(k1, k2)
    }

    @Test
    fun `key is deterministic across instances`() {
        // 幂等：同内容同源 → 同键（IGNORE 入队去重）
        val digest = "AI: 用户偏好深色模式" + "a".repeat(300)
        val k1 = MemoryExtractionJobKey.compute(MemoryExtractionJobEntity.SOURCE_TYPE_AI_DEFERRED, digest)
        val k2 = MemoryExtractionJobKey.compute(MemoryExtractionJobEntity.SOURCE_TYPE_AI_DEFERRED, digest)
        assertEquals(k1, k2)
    }

    @Test
    fun `backoff increases exponentially`() {
        val b0 = MemoryExtractionJobKey.backoffMs(0)
        val b1 = MemoryExtractionJobKey.backoffMs(1)
        val b2 = MemoryExtractionJobKey.backoffMs(2)
        assertTrue("b0=$b0", b0 == 1_000L)
        assertTrue("b1=$b1", b1 == 2_000L)
        assertTrue("b2=$b2", b2 == 4_000L)
    }

    @Test
    fun `backoff capped at one hour`() {
        val big = MemoryExtractionJobKey.backoffMs(20)
        assertTrue("big=$big 应封顶 1h", big <= 3_600_000L)
    }
}
