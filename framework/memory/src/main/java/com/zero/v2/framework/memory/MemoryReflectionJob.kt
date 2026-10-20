package com.zero.v2.framework.memory

import com.zero.v2.core.trace.AgentTraceManager
import com.zero.v2.core.trace.NodeType
import com.zero.v2.services.agent.AgentJob
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Calendar
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 记忆反思任务 — Step 4.8（DESIGN §3.6，AgentJob 由 Scheduler 统一调度，D21）。
 *
 * 每日 Reflection（默认 02:00，§3.4 enableReflection 默认关闭 — D17 用户主权）四段流程:
 *   1. 冲突检测：仅同来源精确重复自动归档，其余保留双方待确认。
 *   2. 过期归档（90 天未访问；5.2 接 ConfigField）
 *   3. LLM 分层压缩（4.7，超阈值触发）
 *   4. 主对话分类派生队列 → 本地事务物化（无副分类模型）
 *
 * 注: 实现于 framework/memory 而非计划路径 services/agent — 4.6-pre 已建立
 * framework/memory → services 依赖（EmbeddingProvider），反向放置会成环；
 * AgentJob 接口实现与模块位置无关（同 AutomationModule 先例）。
 */
@Singleton
class MemoryReflectionJob @Inject constructor(
    private val memoryModule: MemoryModule,
    private val conflictDetector: MemoryConflictDetector,
    private val historyCompressor: HistoryCompressor,
    private val traceManager: AgentTraceManager
) : AgentJob {

    companion object {
        /** 冲突检测频率 → 间隔天数（"手动" = 不自动检测） */
        private val CONFLICT_FREQ_DAYS = mapOf("每天" to 1L, "每周" to 7L, "每月" to 30L)
    }

    /** 上次冲突检测时间（进程内初始化为 now — 宁可少检测不可重复检测） */
    private var lastConflictRunAt = System.currentTimeMillis()

    override val id: String = "memory_reflection"

    /** 关闭 = 暂停（MAX_VALUE）；开启 = 下一个 reflectionHour 整点（过点则明天） */
    override val nextRunTime: Long
        get() {
            if (!memoryModule.config.enableReflection) return Long.MAX_VALUE
            val cal = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, memoryModule.config.reflectionHour.coerceIn(0, 23))
                set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
            }
            if (cal.timeInMillis <= System.currentTimeMillis()) cal.add(Calendar.DAY_OF_YEAR, 1)
            return cal.timeInMillis
        }

    override suspend fun execute() {
        withContext(Dispatchers.IO) {
            traceManager.record("mem_reflection", NodeType.SYSTEM_EVENT, "memory",
                "reflection_start: conflicts=1/4")

        // ── 1. 冲突检测 + 自动解决（§3.6 Step1；频率由 conflictDetectionFrequency 控制，5.2）──
        var resolved = 0
        val cfg = memoryModule.config
        val freqDays = CONFLICT_FREQ_DAYS[cfg.conflictDetectionFrequency]
        if (freqDays == null) {
            traceManager.record("mem_reflection", NodeType.SYSTEM_EVENT, "memory",
                "conflict_skipped: frequency=手动")
        } else if (System.currentTimeMillis() - lastConflictRunAt < freqDays * 86_400_000L) {
            traceManager.record("mem_reflection", NodeType.SYSTEM_EVENT, "memory",
                "conflict_skipped: 频率间隔未到 (${cfg.conflictDetectionFrequency})")
        } else {
            lastConflictRunAt = System.currentTimeMillis()
            try {
                conflictDetector.detectConflicts().forEach { pair ->
                    val strategy = when (pair.judgment) {
                        MemoryConflictDetector.ConflictJudgment.DUPLICATE,
                        MemoryConflictDetector.ConflictJudgment.OUTDATED ->
                            MemoryConflictDetector.ConflictResolution.KEEP_NEWER
                        else -> MemoryConflictDetector.ConflictResolution.KEEP_BOTH_DOWNGRADE  // 矛盾保守降权
                    }
                    if (conflictDetector.resolve(pair, strategy)) resolved++
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled } catch (e: Exception) {
                traceManager.record("mem_reflection", NodeType.ERROR, "memory",
                    "reflection_conflict_failed: ${e.message?.take(50)}")
            }
        }

        // ── 2. 过期归档（archiveAfterDays ConfigField，5.2）──
        val archived = try { memoryModule.archiveExpired(cfg.archiveAfterDays.toLong()) } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled } catch (_: Exception) { 0 }

        // ── 3. 分层压缩（compressionThreshold ConfigField，5.2）──
        val compressed = try { historyCompressor.compressIfNeeded(cfg.compressionThreshold) } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled } catch (_: Exception) { 0 }

        // ── 4. 主对话分类队列 → 本地物化 ──
        val distilled = memoryModule.processPendingDerivations()

        // ── 5. D25: 图属性月度衰减（内部自判 30 天间隔，重启安全）──
        val decayed = try { memoryModule.updateGraphConfidence() } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled } catch (_: Exception) { 0 }

            traceManager.record("mem_reflection", NodeType.SYSTEM_EVENT, "memory",
                "reflection_done: conflicts=$resolved archived=$archived " +
                        "compressed=$compressed distilled=$distilled decayed=$decayed")
        }
    }

}
