package com.zero.v2.core.common

/**
 * 记忆生命周期状态 — Phase 4（归档语义）。
 *
 * ACTIVE   → 参与所有检索
 * ARCHIVED → 过期归档（90 天未访问 / Reflection 判定），不参与常规检索，可浏览
 */
enum class MemoryStatus {
    ACTIVE,
    ARCHIVED
}
