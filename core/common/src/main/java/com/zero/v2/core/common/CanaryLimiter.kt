package com.zero.v2.core.common

/**
 * 灰度限流器（E3 / 设计§5：单用户限定调用 → 晋升/撤回裁决）。
 *
 * 设计§5：灰度先 dry-run/只读样例，再限制调用数和时长（初始建议 5 次/10 分钟）；
 * 校验结果、错误率、延迟和副作用账本；达到门槛后晋升；**失败立即停止路由并恢复上一健康版本**。
 *
 * 纯逻辑（[verdict]）→ JVM 可单测。调用方（ReleaseManager）按裁决晋升 ACTIVE 或回滚。
 */
object CanaryLimiter {
    /** 默认灰度调用上限（设计§5：初始 5 次）。 */
    const val DEFAULT_MAX_CALLS = 5

    /** 默认灰度观察窗口（设计§5：10 分钟）。 */
    const val DEFAULT_WINDOW_MS = 10L * 60 * 1000

    /** 灰度状态快照（调用方累计 + 落账本）。 */
    data class CanaryState(
        val calls: Int,
        val firstCallAt: Long,
        val failures: Int,
        val now: Long,
    )

    /** 灰度裁决。 */
    enum class Verdict { CONTINUE, PROMOTE, ROLLBACK }

    /**
     * 裁决灰度去向：
     * - **ROLLBACK**：有失败 → 立即停止路由 + 恢复上一健康（设计§5"失败立即停止路由"）。
     * - **PROMOTE**：达调用上限 或 观察窗结束，且无失败 → 晋升 ACTIVE。
     * - **CONTINUE**：窗口内 + 未达上限 + 无失败 → 继续灰度。
     */
    fun verdict(
        state: CanaryState,
        maxCalls: Int = DEFAULT_MAX_CALLS,
        windowMs: Long = DEFAULT_WINDOW_MS,
    ): Verdict {
        // 失败优先回滚（不等到上限——设计§5"失败立即停止路由"）
        if (state.failures > 0) return Verdict.ROLLBACK
        // 无调用 → 继续（灰度未开始或刚启动）
        if (state.calls == 0) return Verdict.CONTINUE
        // 达调用上限 + 无失败 → 晋升
        if (state.calls >= maxCalls) return Verdict.PROMOTE
        // 观察窗结束 + 无失败 + 有调用 → 晋升（窗口内零失败即达标）
        if (state.now - state.firstCallAt >= windowMs) return Verdict.PROMOTE
        // 窗口内 + 未达上限 + 无失败 → 继续
        return Verdict.CONTINUE
    }

    /** 是否已超观察窗（调用方判定灰度是否应结束——超窗+无失败即晋升）。 */
    fun windowElapsed(state: CanaryState, windowMs: Long = DEFAULT_WINDOW_MS): Boolean =
        state.calls > 0 && state.now - state.firstCallAt >= windowMs
}
