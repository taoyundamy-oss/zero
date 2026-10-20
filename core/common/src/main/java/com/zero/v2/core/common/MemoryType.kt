package com.zero.v2.core.common

/**
 * 记忆语义类型 — Phase 4（DESIGN §3.7 MemoryScoringEngine 的 typeWeight 维度）。
 *
 * 与 Tier 正交：Tier 决定存储策略（T0 丢弃/T1 月级/T2 永久），
 * Type 决定检索权重与演化行为（归档半衰期、冲突检测范围）。
 */
enum class MemoryType {
    /** 用户明确要求长期遵循的规范；不得从模型推断升级。 */
    DIRECTIVE,
    /** 长期事实（"用户住在邵阳"）— typeWeight 0.8 */
    FACT,
    /** 偏好（"用户喜欢科幻"）— typeWeight 1.0 */
    PREFERENCE,
    /** 事件（"上周参加了面试"）— typeWeight 0.6，半衰期 7 天 */
    EVENT,
    /** 关系（"张三是同事"）— typeWeight 0.9 */
    RELATIONSHIP,
    /** 技能（"会 Rust"）— typeWeight 0.85 */
    SKILL,
    /** 任务（"打算买 MacBook"）— typeWeight 0.7，半衰期 14 天 */
    TASK,
    /** 洞察（Reflection 提炼）— typeWeight 0.75 */
    INSIGHT
}
