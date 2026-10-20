package com.zero.v2.core.common

/**
 * 动态角色风格指令（P3 / 设计§6）。
 *
 * 把 [AffinityBand] + [Mood] 投影为一段 ≤120 token 的短风格指令，注入本轮可变 turnContext
 * （C1 turnContext 槽位 / C2 styleMax≤120 已就位）。**不入 stablePrefix**——动态状态变化不
 * 扰动前缀缓存；固定人设仍留在 stablePrefix，无状态记录时本指令为 null → 继续用固定 systemPrompt。
 *
 * 设计§6 约束：
 * - **只影响表达风格**（D1）：语气/亲切度/简短度，不改权限/安全/事实/工具/系统角色底线。
 * - **不暴露内部计分给模型**：不出现 "好感 73" / 档位英文名（ACQUAINTANCE/DEVOTED 等）；
 *   只给定性表达提示（"温和"、"亲切、可更直接简短"）。
 * - **无状态记录 → 固定 systemPrompt**：affinity=DEFAULT 且 mood=DEFAULT → 返回 null（不注入）。
 * - **可关**：[enabled]=false → 返回 null（关闭动态表达，存量状态不删除，固定人设继续回复）。
 *
 * 纯函数无副作用——JVM 可单测；DAO 读取与 reducer 写入在 CharacterStateModule（framework:chat）。
 */
object CharacterStyleDirective {

    /** C2 styleMax≤120 token；中文按 ~2 字符/token 保守估，240 字符为硬上限（防未来标签膨胀）。 */
    const val MAX_CHARS = 240

    const val MAX_TOKENS = 120

    /**
     * 组合风格指令。
     *
     * @param affinity 当前亲密度档位（[AffinityBand.DEFAULT]=无记录）
     * @param mood 当前会话情绪（[Mood.DEFAULT]=无记录）
     * @param enabled 动态表达开关（角色页可关）
     * @return ≤[MAX_CHARS] 字符的指令字符串，或 null（关闭 / 无状态记录 → 不注入）
     */
    fun compose(affinity: AffinityBand, mood: Mood, enabled: Boolean): String? {
        if (!enabled) return null
        // 无状态记录（双默认）→ 不注入：固定 systemPrompt 继续生效（设计§6 验收项）
        if (affinity == AffinityBand.DEFAULT && mood == Mood.DEFAULT) return null

        val tone = toneFor(mood)
        val warmth = warmthFor(affinity)
        val descriptors = listOfNotNull(tone, warmth)
        // 双默认已在上面 return；此处 descriptors 至少一个
        if (descriptors.isEmpty()) return null

        val expr = buildString {
            append("表达语气：")
            append(descriptors.joinToString("、"))
            append("；保持既有事实与安全规则")
        }
        return if (expr.length <= MAX_CHARS) expr else expr.take(MAX_CHARS)
    }

    /** 情绪→即时语气词（NEUTRAL 不加语气前缀——由亲切度单独驱动或单纯保持自然）。 */
    private fun toneFor(mood: Mood): String? = when (mood) {
        Mood.NEUTRAL -> null
        Mood.WARM -> "温和"
        Mood.CONCERNED -> "关切、克制"
        Mood.CHEERFUL -> "轻快、明朗"
    }

    /** 亲密度→亲切度/简短度词（ACQUAINTANCE 不加——默认初识不加修饰；不暴露档位名/数值）。 */
    private fun warmthFor(affinity: AffinityBand): String? = when (affinity) {
        AffinityBand.ACQUAINTANCE -> null
        AffinityBand.WARM -> "自然"
        AffinityBand.CLOSE -> "亲切、可更直接"
        AffinityBand.DEVOTED -> "亲切、可更直接简短"
    }
}
