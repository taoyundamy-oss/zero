package com.zero.v2.framework.memory

import com.zero.v2.core.common.MemoryStatus
import com.zero.v2.core.data.entity.MemoryEntity
import com.zero.v2.core.trace.AgentTraceManager
import com.zero.v2.core.trace.NodeType
import com.zero.v2.services.llm.ModelCapability
import com.zero.v2.services.llm.ModelSelector
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 记忆冲突检测器 — DESIGN §3.5（Step 4.5）。
 *
 * 流程: ACTIVE 同类型/范围的事实、偏好与规范两两语义相似（余弦 ≥ 0.8，经 MemoryModule.findSimilar —
 * D31 Embedding 主径）→ LLM 精判四值 → 冲突对交由解决策略（§3.6 ReflectionJob 调用）。
 *
 * 判定优先级: 内容全等 → DUPLICATE（免 LLM）；其余 → LLM。
 * 成本: 每对一次 chatSync（~100 token），仅对相似对调用，量级可控。
 */
@Singleton
class MemoryConflictDetector @Inject constructor(
    private val memoryModule: MemoryModule,
    private val modelSelector: ModelSelector,
    private val traceManager: AgentTraceManager
) {

    /** LLM 四值判定 */
    enum class ConflictJudgment { CONTRADICTION, OUTDATED, COMPATIBLE, DUPLICATE }

    /** 解决策略（§3.5 ConflictResolution；ASK_USER 待确认弹窗 Phase 8 接入） */
    enum class ConflictResolution { KEEP_NEWER, KEEP_HIGHER_CONFIDENCE, MERGE, ASK_USER, KEEP_BOTH_DOWNGRADE }

    /** 冲突对：a 为基准（较新优先），b 为邻居 */
    data class MemoryPair(val a: MemoryEntity, val b: MemoryEntity, val judgment: ConflictJudgment)

    /**
     * 全量冲突检测（4.8 ReflectionJob 调用）。
     * @return 判定为 CONTRADICTION / OUTDATED / DUPLICATE 的对
     */
    suspend fun detectConflicts(): List<MemoryPair> = withContext(Dispatchers.IO) {
        traceManager.record("mem_conflict", NodeType.SYSTEM_EVENT, "memory",
            "detect_start")

        val facts = memoryModule.activeConflictCandidates()
        val conflicts = mutableListOf<MemoryPair>()
        var llmCalls = 0
        val visited = mutableSetOf<Pair<String, String>>()

        scan@ for (fact in facts) {
            val similars = memoryModule.findSimilar(fact.id, minSimilarity = 0.8f, limit = 3)
            for (neighbor in similars) {
                if (!MemoryConflictPolicy.eligible(fact, neighbor)) continue
                val (newer, older) = MemoryConflictPolicy.ordered(fact, neighbor)
                if (!visited.add(newer.id to older.id)) continue
                if (memoryModule.hasConflictDecision(newer, older)) continue
                // 内容全等 → DUPLICATE 短路（零 LLM 成本）
                val judgment = if (fact.content.trim() == neighbor.content.trim()) {
                    ConflictJudgment.DUPLICATE
                } else {
                    if (llmCalls >= 16) break@scan
                    llmCalls++
                    judgeWithLLM(newer, older) ?: continue
                }
                if (judgment != ConflictJudgment.COMPATIBLE) {
                    conflicts.add(MemoryPair(newer, older, judgment))
                    traceManager.record("mem_conflict", NodeType.SYSTEM_EVENT, "memory",
                        "conflict_found: judgment=$judgment")
                }
            }
        }

        traceManager.record("mem_conflict", NodeType.SYSTEM_EVENT, "memory",
            "detect_done: facts=${facts.size} pairs=${conflicts.size} llmCalls=$llmCalls")
        conflicts
    }

    /**
     * 解决一个冲突对（§3.6）。
     * 仅同来源的精确重复可以自动归档；模型推断的旧版/矛盾保留双方。
     * 未自动应用的候选进入持久冲突列表，等待用户确认；未应用不能记为解决。
     */
    suspend fun resolve(pair: MemoryPair, resolution: ConflictResolution) = withContext(Dispatchers.IO) {
        val applied = resolution == ConflictResolution.KEEP_NEWER &&
            pair.judgment == ConflictJudgment.DUPLICATE &&
            memoryModule.consolidateDuplicate(pair.a, pair.b)
        if (!applied) memoryModule.recordConflict(pair.a, pair.b, pair.judgment.name)
        traceManager.record("mem_conflict", NodeType.SYSTEM_EVENT, "memory",
            "conflict_decision: strategy=$resolution judgment=${pair.judgment} applied=$applied")
        applied
    }

    // ═══ 内部 ═══

    private suspend fun judgeWithLLM(a: MemoryEntity, b: MemoryEntity): ConflictJudgment? {
        val model = modelSelector.select(setOf(ModelCapability.CHAT)) ?: return null
        return try {
            val prompt = """
                判断两条用户记忆的关系。A:"${a.content.take(300)}" B:"${b.content.take(300)}"
                返回严格 JSON（无其他文字）: {"judgment": "CONTRADICTION 或 OUTDATED 或 COMPATIBLE 或 DUPLICATE"}
                语义: CONTRADICTION=互相矛盾 / OUTDATED=B 是 A 的过时旧版 / COMPATIBLE=兼容共存 / DUPLICATE=重复
            """.trimIndent()
            val response = model.chatSync(prompt, "你是记忆一致性判断引擎，只输出 JSON。")
            val json = com.zero.v2.services.module.JsonExtractor.extract(response) ?: return null
            val raw = json.substringAfter(":").replace(Regex("[\"}\\s]"), "")
            when {
                raw.contains("CONTRADICTION") -> ConflictJudgment.CONTRADICTION
                raw.contains("OUTDATED") -> ConflictJudgment.OUTDATED
                raw.contains("DUPLICATE") -> ConflictJudgment.DUPLICATE
                raw.contains("COMPATIBLE") -> ConflictJudgment.COMPATIBLE
                else -> null
            }
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
    }

}
