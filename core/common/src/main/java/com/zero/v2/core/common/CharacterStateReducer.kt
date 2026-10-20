package com.zero.v2.core.common

/**
 * 角色状态 Reducer — P2（设计§6：确定性规则约束的状态变化引擎）。
 *
 * 用户/助手回合完成后读取可用文本证据 → 输出候选变化（affinity/mood）。纯规则驱动，
 * 不调 LLM、不占 MessagePipeline 100ms filter 路径（回合后异步调用）。
 *
 * 确定性规则（设计§6）：
 * - **单轮最大变化**：亲密度每轮最多 +1 档（不跳级）；情绪每轮最多 1 次设定。
 * - **冷却**：亲密度变化有冷却期（默认 6h），冷却内不变（防刷分）。
 * - **无关消息不变**：天气查询/工具结果/短指令/查询打听不触发变化。
 * - **负面/争执不自动惩罚用户**：争执时情绪可变 concerned，但**亲密度不降**（不因争执自动疏远）。
 * - **情绪 TTL**：情绪有有效期（默认 30min），过期衰减回 neutral。
 * - **亲密度按角色累积**：跨会话累积，仅正向信号推进（+1 档，封顶 DEVOTED），不自动降档。
 *
 * **不改变** SAFE/CONFIRM/FORBIDDEN、工具权限、事实来源、危机响应等固定规则（D1）。
 * 纯函数无副作用——写入经 P1 CharacterStateDao 事务（调用方）。
 */
class CharacterStateReducer(
    /** 亲密度变化冷却（默认 6h——防短时刷分）。 */
    val affinityCooldownMs: Long = 6L * 3600_000,
    /** 情绪 TTL（默认 30min——短时衰减回 neutral）。 */
    val moodTtlMs: Long = 30L * 60_000,
) {
    /** Reducer 输入（当前状态 + 本轮文本 + 时间）。 */
    data class Input(
        val userText: String,
        val assistantText: String,
        val currentAffinity: AffinityBand,
        val currentMood: Mood,
        val moodExpiresAt: Long,
        val lastAffinityChangeAt: Long,
        val now: Long,
    )

    /** 候选状态变化提案（供调用方写 DAO + 事件）。null 字段=不变。 */
    data class Output(
        val newAffinity: AffinityBand?,
        val newMood: Mood?,
        val newMoodIntensity: Float,
        val newMoodExpiresAt: Long,
        val reasonAffinity: String?,
        val reasonMood: String?,
    ) {
        companion object {
            val NO_CHANGE = Output(null, null, 0f, 0, null, null)
        }
    }

    /** 主入口：读文本证据 + 当前状态 → 输出候选变化。 */
    fun reduce(input: Input): Output {
        // 1. 无关消息 → 不变（天气/工具结果/短指令/查询）；情绪可能衰减
        if (isIrrelevant(input.userText)) return moodDecayOnly(input)

        val now = input.now
        val signals = classifySignals(input.userText)
        if (!signals.positive && !signals.negative && !signals.cheerful) return moodDecayOnly(input)

        // 2. 情绪（即时事件驱动，有 TTL）
        val newMood: Mood? = when {
            signals.negative -> Mood.CONCERNED
            signals.cheerful -> Mood.CHEERFUL
            signals.positive -> Mood.WARM
            else -> null
        }
        val newMoodIntensity = if (newMood != null && newMood != Mood.NEUTRAL) 0.6f else 0f
        val newMoodExpiresAt = if (newMood != null) now + moodTtlMs else input.moodExpiresAt
        val reasonMood: String? = when (newMood) {
            Mood.WARM -> "温和交流"
            Mood.CHEERFUL -> "欢快互动"
            Mood.CONCERNED -> "负面/争执（亲密度不降）"
            else -> null
        }

        // 3. 亲密度（仅正向推进，单轮 +1 封顶，冷却内不变；负面不降）
        var newAffinity: AffinityBand? = null
        var reasonAffinity: String? = null
        if (!signals.negative && signals.positive && signals.affinityUp) {
            val cooledDown = now - input.lastAffinityChangeAt >= affinityCooldownMs
            if (cooledDown) {
                val next = nextBandUp(input.currentAffinity)
                if (next != null && next != input.currentAffinity) {
                    newAffinity = next
                    reasonAffinity = "正向信号推进"
                }
            }
        }
        // 负面/争执：newAffinity 保持 null（不降，不自动惩罚用户）

        return Output(
            newAffinity = newAffinity,
            newMood = newMood,
            newMoodIntensity = newMoodIntensity,
            newMoodExpiresAt = newMoodExpiresAt,
            reasonAffinity = reasonAffinity,
            reasonMood = reasonMood,
        )
    }

    /** 无关消息判定（天气/搜索/工具确认/短指令/查询打听）。无信号短消息自然走 NO_CHANGE。 */
    private fun isIrrelevant(userText: String): Boolean {
        val t = userText.trim()
        if (t.isBlank()) return true
        val irrelevant = listOf(
            "帮我查", "搜索", "今天天气", "天气怎么样", "几点了", "现在时间",
            "打开", "关闭", "设置提醒", "播放", "读一下", "翻译"
        )
        return irrelevant.any { it in t }
    }

    private data class Signals(val positive: Boolean, val negative: Boolean, val cheerful: Boolean, val affinityUp: Boolean)

    /** 情绪/亲密度信号分类（正向/负向 + 是否推进亲密度 + 是否欢快）。 */
    private fun classifySignals(userText: String): Signals {
        // Quoted/reported statements are not the user's relationship evidence.
        val t = userText.replace(Regex("“[^”]*”|‘[^’]*’|\"[^\"]*\"|`[^`]*`"), "")
        if (listOf("他说", "她说", "例子", "例如", "假设", "如果", "引用", "翻译").any { it in t }) {
            return Signals(false, false, false, false)
        }
        val negated = Regex("(?:不|没|别|未)(?:再|太|很|觉得|感到|那么|怎么)?(?:喜欢|信任|依赖|开心|舒服|爱)").containsMatchIn(t)
        val positive = listOf("喜欢", "谢谢", "感谢", "很好", "太棒了", "你真", "我爱", "开心", "舒服")
            .any { it in t }
        val cheerful = listOf("哈哈", "太棒了", "好开心", "耶", "太好啦").any { it in t }
        val negative = negated || listOf("讨厌", "烦", "不高兴", "生气", "别这样", "不喜欢你", "滚", "闭嘴")
            .any { it in t }
        val affinityUp = listOf("喜欢你", "我爱你", "你真", "依赖你", "信任你")
            .any { it in t }
        return Signals(positive = positive, negative = negative, cheerful = cheerful, affinityUp = affinityUp)
    }

    /** 单轮 +1 档（封顶 DEVOTED，不跳级）。 */
    private fun nextBandUp(current: AffinityBand): AffinityBand? = when (current) {
        AffinityBand.ACQUAINTANCE -> AffinityBand.WARM
        AffinityBand.WARM -> AffinityBand.CLOSE
        AffinityBand.CLOSE -> AffinityBand.DEVOTED
        AffinityBand.DEVOTED -> null  // 已封顶
    }

    /** 仅情绪衰减（无关消息时——情绪过期回 neutral，亲密度不动）。 */
    private fun moodDecayOnly(input: Input): Output {
        // 已过期（expiresAt 非零且 ≤ now）→ 衰减回 neutral
        val expired = input.currentMood != Mood.DEFAULT &&
            input.moodExpiresAt > 0 && input.moodExpiresAt <= input.now
        return if (expired) {
            Output(null, Mood.NEUTRAL, 0f, 0, null, "情绪过期衰减")
        } else Output.NO_CHANGE
    }
}
