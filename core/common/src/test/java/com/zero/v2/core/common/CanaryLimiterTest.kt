package com.zero.v2.core.common

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [CanaryLimiter] 单测 — E3 验收：灰度限次/时长 → promote/rollback 裁决。
 *
 * 设计§5：5 次/10 分钟初始；失败立即停止路由；达门槛晋升。
 */
class CanaryLimiterTest {

    @Test
    fun noCalls_continues() {
        val s = CanaryLimiter.CanaryState(calls = 0, firstCallAt = 0, failures = 0, now = 0)
        assertEquals(CanaryLimiter.Verdict.CONTINUE, CanaryLimiter.verdict(s))
    }

    @Test
    fun withinWindowUnderLimit_continues() {
        val s = CanaryLimiter.CanaryState(calls = 3, firstCallAt = 1000, failures = 0, now = 2000)
        assertEquals(CanaryLimiter.Verdict.CONTINUE, CanaryLimiter.verdict(s))
    }

    @Test
    fun reachesMaxCalls_promotes() {
        // 5 次调用 + 无失败 → PROMOTE
        val s = CanaryLimiter.CanaryState(calls = 5, firstCallAt = 1000, failures = 0, now = 2000)
        assertEquals(CanaryLimiter.Verdict.PROMOTE, CanaryLimiter.verdict(s))
    }

    @Test
    fun windowElapsed_promotes() {
        // 超观察窗（10min）+ 有调用 + 无失败 → PROMOTE
        val start = 0L
        val now = CanaryLimiter.DEFAULT_WINDOW_MS + 1000  // 超 10 分钟
        val s = CanaryLimiter.CanaryState(calls = 2, firstCallAt = start, failures = 0, now = now)
        assertEquals(CanaryLimiter.Verdict.PROMOTE, CanaryLimiter.verdict(s))
    }

    @Test
    fun anyFailure_rollbacksImmediately() {
        // 设计§5"失败立即停止路由"——不等到上限或窗口
        val s = CanaryLimiter.CanaryState(calls = 1, firstCallAt = 1000, failures = 1, now = 2000)
        assertEquals(CanaryLimiter.Verdict.ROLLBACK, CanaryLimiter.verdict(s))
    }

    @Test
    fun failureOverridesMaxCalls() {
        // 即使达上限，有失败也回滚（不晋升坏版本）
        val s = CanaryLimiter.CanaryState(calls = 5, firstCallAt = 1000, failures = 1, now = 2000)
        assertEquals(CanaryLimiter.Verdict.ROLLBACK, CanaryLimiter.verdict(s))
    }

    @Test
    fun customLimitsRespected() {
        // 自定义 3 次/5 分钟——3 次无失败晋升
        val s = CanaryLimiter.CanaryState(calls = 3, firstCallAt = 0, failures = 0, now = 1000)
        assertEquals(CanaryLimiter.Verdict.PROMOTE,
            CanaryLimiter.verdict(s, maxCalls = 3, windowMs = 5 * 60 * 1000))
    }

    @Test
    fun windowElapsed_atBoundaryNotYetPromoted() {
        // 边界：now == firstCallAt + windowMs（>=边界即晋升）
        val s = CanaryLimiter.CanaryState(calls = 2, firstCallAt = 0, failures = 0, now = CanaryLimiter.DEFAULT_WINDOW_MS)
        assertEquals(CanaryLimiter.Verdict.PROMOTE, CanaryLimiter.verdict(s))
    }
}
