package com.zero.v2.app.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [ZeroMotion.durationMs] 单测 — U3 验收：动效时长 + 减少动效降级（纯函数 JVM 可测）。
 */
class ZeroMotionTest {

    @Test
    fun normalMotion_usesTokenDuration() {
        assertEquals(ZeroTokens.MOTION_STATE_MS, ZeroMotion.durationMs(false, ZeroTokens.MOTION_STATE_MS))
        assertEquals(ZeroTokens.MOTION_FEEDBACK_MS, ZeroMotion.durationMs(false, ZeroTokens.MOTION_FEEDBACK_MS))
        assertEquals(180, ZeroMotion.durationMs(false, 180))
    }

    @Test
    fun reducedMotion_zeroDuration() {
        // 减少动效 → 0 即时（无障碍"减少动态"铁律）
        assertEquals(0, ZeroMotion.durationMs(true, ZeroTokens.MOTION_STATE_MS))
        assertEquals(0, ZeroMotion.durationMs(true, ZeroTokens.MOTION_FEEDBACK_MS))
        assertEquals(0, ZeroMotion.durationMs(true, 1000))
    }

    @Test
    fun reducedMotion_overridesAnyBase() {
        // 任意 baseMs，reduceMotion=true 恒 0
        for (base in listOf(0, 1, 120, 180, 280, 320, 1800)) {
            assertEquals(0, ZeroMotion.durationMs(true, base))
        }
    }
}
