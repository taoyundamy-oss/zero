package com.zero.v2.framework.memory

import com.zero.v2.core.common.MemoryType
import com.zero.v2.core.data.entity.MemoryEntity
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.exp
import kotlin.math.ln

/**
 * 记忆评分引擎 — DESIGN §3.7（Step 4.3）。
 *
 * score = queryRelevance × typeWeight × timeDecay × importance × confidence × reinforcementBonus
 *
 *   - typeWeight:      语义类型权重（PREFERENCE 最高 1.0，EVENT 最低 0.6）
 *   - timeDecay:       指数遗忘曲线 exp(-days·ln2/halfLife)，按类型半衰期
 *                      （EVENT 7 天 / TASK 14 天 / 其余 90 天 — 以最后访问时间起算）
 *   - reinforcement:   每次检索命中 +0.1，上限 2.0（越用越强的记忆）
 *   - queryRelevance:  查询相关度 0-1（D31: 由 Embedding 余弦供给；
 *                      4.6-pre 前 keyword 路径调用方可传 1.0 或词面相关度）
 *
 * 纯函数无副作用 — 不埋 AgentTrace（埋点点位在 4.6 检索调用处）。
 */
@Singleton
class MemoryScoringEngine @Inject constructor() {

    /** 单条记忆的综合得分 */
    fun score(memory: MemoryEntity, queryRelevance: Float, now: Long = System.currentTimeMillis()): Float {
        return queryRelevance.coerceIn(0f, 1f) * typeWeight(memory.type) *
                timeDecay(memory, now) *
                memory.importance.coerceIn(0f, 1f) *
                memory.confidence.coerceIn(0f, 1f) *
                reinforcementBonus(memory.reinforcementCount)
    }

    /** 按得分降序排序（4.6 混合检索的排序入口） */
    fun rank(
        memories: List<MemoryEntity>,
        relevanceOf: (MemoryEntity) -> Float,
        now: Long = System.currentTimeMillis()
    ): List<MemoryEntity> {
        return memories.sortedByDescending { score(it, relevanceOf(it), now) }
    }

    /** 语义类型权重（§3.7 typeWeight 表） */
    private fun typeWeight(type: MemoryType): Float = when (type) {
        MemoryType.DIRECTIVE -> 1.0f
        MemoryType.FACT -> 0.8f
        MemoryType.PREFERENCE -> 1.0f
        MemoryType.EVENT -> 0.6f
        MemoryType.RELATIONSHIP -> 0.9f
        MemoryType.SKILL -> 0.85f
        MemoryType.TASK -> 0.7f
        MemoryType.INSIGHT -> 0.75f
    }

    /** 指数遗忘：按类型的最后访问半衰期 */
    private fun timeDecay(memory: MemoryEntity, now: Long): Float {
        val halfLifeDays = when (memory.type) {
            MemoryType.EVENT -> 7f
            MemoryType.TASK -> 14f
            else -> 90f
        }
        val daysSinceAccess = (now - memory.lastAccessedAt) / (24 * 3600_000f)
        return exp(-daysSinceAccess * ln(2f) / halfLifeDays)
    }

    /** 强化加成：每次命中 +0.1，上限 2.0 */
    private fun reinforcementBonus(count: Int): Float = minOf(1f + count * 0.1f, 2.0f)
}
