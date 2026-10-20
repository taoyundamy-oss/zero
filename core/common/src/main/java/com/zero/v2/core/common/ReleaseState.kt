package com.zero.v2.core.common

/**
 * 动态模块发布状态机（E1 / 设计§5）。
 *
 * `PROPOSED → STAGED → VALIDATING → READY → CANARY → ACTIVE`
 * 任意失败 → `FAILED`；灰度失败 → `ROLLING_BACK → ROLLED_BACK`。
 * 进程恢复从 journal 继续，不重新执行已完成副作用（幂等）。
 *
 * 纯逻辑（[canTransitionTo] 校验合法迁移）→ JVM 可单测。
 * 设计§5："activation 前写预提交 journal；探活成功后原子切活动记录"——状态机驱动 journal 落盘。
 */
enum class ReleaseState {
    /** 提案——LLM/用户提议新版本（仅 journal 记录，无文件副作用）。 */
    PROPOSED,
    /** 已暂存——代码写入 staging/<jobId>/，hash 计算完成（不动 active）。 */
    STAGED,
    /** 校验中——语法/manifest/契约/探活执行（不动 active）。 */
    VALIDATING,
    /** 就绪——校验通过，可激活（不动 active）。 */
    READY,
    /** 灰度中——有限调用数/时长观测（E3；活动指针已切但限流）。 */
    CANARY,
    /** 活动——正式路由（活动指针指向此版本）。 */
    ACTIVE,
    /** 失败——校验/探活失败（不进 active，可重提）。 */
    FAILED,
    /** 回滚中——灰度失败，恢复上一健康版本。 */
    ROLLING_BACK,
    /** 已回滚——恢复完成。 */
    ROLLED_BACK,
    ;

    /** 合法状态迁移（设计§5 状态机）。 */
    fun canTransitionTo(next: ReleaseState): Boolean = next in allowedNext()

    /** 从当前状态合法的下一状态集合。 */
    fun allowedNext(): Set<ReleaseState> = when (this) {
        PROPOSED -> setOf(STAGED, FAILED)
        STAGED -> setOf(VALIDATING, FAILED)
        VALIDATING -> setOf(READY, FAILED)
        READY -> setOf(CANARY, ACTIVE, FAILED)
        CANARY -> setOf(ACTIVE, ROLLING_BACK, FAILED)
        ACTIVE -> setOf(ROLLING_BACK)
        FAILED -> setOf(PROPOSED)  // 重提（新 deploymentId）
        ROLLING_BACK -> setOf(ROLLED_BACK, FAILED)
        ROLLED_BACK -> setOf(PROPOSED)  // 重新提案
    }

    /** 是否为终态（不可再迁移除非重提新 deploymentId）。 */
    fun isTerminal(): Boolean = this == FAILED || this == ROLLED_BACK

    companion object {
        /** 进程恢复时从 journal 状态决定续跑动作（设计§5：不重新执行已完成副作用）。 */
        fun resumeAction(state: ReleaseState): ResumeAction = when (state) {
            PROPOSED -> ResumeAction.STAGE
            STAGED -> ResumeAction.VALIDATE
            VALIDATING -> ResumeAction.REVALIDATE
            READY -> ResumeAction.ACTIVATE
            CANARY -> ResumeAction.PROMOTE_OR_ROLLBACK
            ACTIVE -> ResumeAction.NONE
            FAILED -> ResumeAction.NONE
            ROLLING_BACK -> ResumeAction.CONTINUE_ROLLBACK
            ROLLED_BACK -> ResumeAction.NONE
        }
    }
}

/** 进程恢复续跑动作（幂等——已完成的不重做）。 */
enum class ResumeAction {
    STAGE,              // 写 staging（若未写）
    VALIDATE,           // 启动校验
    REVALIDATE,         // 重新校验（中断在 VALIDATING——重跑校验幂等）
    ACTIVATE,           // 切活动指针
    PROMOTE_OR_ROLLBACK,// 灰度决策（E3）
    CONTINUE_ROLLBACK,  // 续回滚
    NONE,               // 终态/活动——无需续跑
}
