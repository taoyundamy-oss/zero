package com.zero.v2.core.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [ReleaseState] 状态机单测 — E1 验收：合法迁移 + 进程恢复续跑动作。
 *
 * 设计§5：`PROPOSED→STAGED→VALIDATING→READY→CANARY→ACTIVE`；失败→FAILED；灰度失败→ROLLING_BACK→ROLLED_BACK。
 */
class ReleaseStateTest {

    @Test
    fun forwardPath_allowsTransitions() {
        // PROPOSED→STAGED→VALIDATING→READY→CANARY→ACTIVE 全链路合法
        assertTrue(ReleaseState.PROPOSED.canTransitionTo(ReleaseState.STAGED))
        assertTrue(ReleaseState.STAGED.canTransitionTo(ReleaseState.VALIDATING))
        assertTrue(ReleaseState.VALIDATING.canTransitionTo(ReleaseState.READY))
        assertTrue(ReleaseState.READY.canTransitionTo(ReleaseState.CANARY))
        assertTrue(ReleaseState.CANARY.canTransitionTo(ReleaseState.ACTIVE))
        // READY 也可直跳 ACTIVE（跳过灰度——低风险声明式模块可）
        assertTrue(ReleaseState.READY.canTransitionTo(ReleaseState.ACTIVE))
    }

    @Test
    fun failurePath_entersFailed() {
        // 任意校验阶段失败 → FAILED
        assertTrue(ReleaseState.PROPOSED.canTransitionTo(ReleaseState.FAILED))
        assertTrue(ReleaseState.STAGED.canTransitionTo(ReleaseState.FAILED))
        assertTrue(ReleaseState.VALIDATING.canTransitionTo(ReleaseState.FAILED))
        assertTrue(ReleaseState.READY.canTransitionTo(ReleaseState.FAILED))
        assertTrue(ReleaseState.CANARY.canTransitionTo(ReleaseState.FAILED))
    }

    @Test
    fun rollbackPath_allowsFromActiveAndCanary() {
        // ACTIVE → ROLLING_BACK（用户/灰度失败触发）；CANARY → ROLLING_BACK（灰度失败）
        assertTrue(ReleaseState.ACTIVE.canTransitionTo(ReleaseState.ROLLING_BACK))
        assertTrue(ReleaseState.CANARY.canTransitionTo(ReleaseState.ROLLING_BACK))
        assertTrue(ReleaseState.ROLLING_BACK.canTransitionTo(ReleaseState.ROLLED_BACK))
    }

    @Test
    fun illegalTransitions_rejected() {
        // 不能跳级：PROPOSED 不能直接 ACTIVE
        assertFalse(ReleaseState.PROPOSED.canTransitionTo(ReleaseState.ACTIVE))
        assertFalse(ReleaseState.PROPOSED.canTransitionTo(ReleaseState.READY))
        // ACTIVE 不能回 READY（只能 ROLLING_BACK）
        assertFalse(ReleaseState.ACTIVE.canTransitionTo(ReleaseState.READY))
        // FAILED 不能直接 ACTIVE（须新 deploymentId 重提）
        assertFalse(ReleaseState.FAILED.canTransitionTo(ReleaseState.ACTIVE))
    }

    @Test
    fun resumeAction_recoversFromJournalState() {
        // 进程恢复——不重新执行已完成副作用（幂等）
        assertEquals(ResumeAction.STAGE, ReleaseState.resumeAction(ReleaseState.PROPOSED))
        assertEquals(ResumeAction.VALIDATE, ReleaseState.resumeAction(ReleaseState.STAGED))
        assertEquals(ResumeAction.REVALIDATE, ReleaseState.resumeAction(ReleaseState.VALIDATING))
        assertEquals(ResumeAction.ACTIVATE, ReleaseState.resumeAction(ReleaseState.READY))
        assertEquals(ResumeAction.PROMOTE_OR_ROLLBACK, ReleaseState.resumeAction(ReleaseState.CANARY))
        assertEquals(ResumeAction.CONTINUE_ROLLBACK, ReleaseState.resumeAction(ReleaseState.ROLLING_BACK))
        assertEquals(ResumeAction.NONE, ReleaseState.resumeAction(ReleaseState.ACTIVE))
        assertEquals(ResumeAction.NONE, ReleaseState.resumeAction(ReleaseState.FAILED))
        assertEquals(ResumeAction.NONE, ReleaseState.resumeAction(ReleaseState.ROLLED_BACK))
    }

    @Test
    fun isTerminal_failedAndRolledBack() {
        assertTrue(ReleaseState.FAILED.isTerminal())
        assertTrue(ReleaseState.ROLLED_BACK.isTerminal())
        assertFalse(ReleaseState.ACTIVE.isTerminal())
        assertFalse(ReleaseState.READY.isTerminal())
    }
}
